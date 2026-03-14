uniffi::include_scaffolding!("omnimem");

use std::sync::Mutex;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum OmniMemError {
    #[error("Audio processing error")]
    AudioError,
    #[error("Network error")]
    NetworkError,
    #[error("Internal error")]
    InternalError,
}

use std::io::Write;
use reqwest::blocking::{Client, multipart};
use serde_json::Value;

pub struct OmniMemSession {
    audio_buffer: Mutex<Vec<u8>>,
    partial_text: Mutex<String>,
    api_key: String,
}

fn create_wav_header(data_len: u32, sample_rate: u32, channels: u16, bits_per_sample: u16) -> Vec<u8> {
    let mut header = Vec::with_capacity(44);
    let byte_rate = sample_rate * channels as u32 * (bits_per_sample / 8) as u32;
    let block_align = channels * (bits_per_sample / 8);

    header.extend_from_slice(b"RIFF");
    header.write_all(&(36 + data_len).to_le_bytes()).unwrap();
    header.extend_from_slice(b"WAVE");
    
    header.extend_from_slice(b"fmt ");
    header.write_all(&16u32.to_le_bytes()).unwrap();
    header.write_all(&1u16.to_le_bytes()).unwrap();
    header.write_all(&channels.to_le_bytes()).unwrap();
    header.write_all(&sample_rate.to_le_bytes()).unwrap();
    header.write_all(&byte_rate.to_le_bytes()).unwrap();
    header.write_all(&block_align.to_le_bytes()).unwrap();
    header.write_all(&bits_per_sample.to_le_bytes()).unwrap();
    
    header.extend_from_slice(b"data");
    header.write_all(&data_len.to_le_bytes()).unwrap();
    
    header
}

impl OmniMemSession {
    pub fn new(api_key: String) -> Self {
        Self {
            audio_buffer: Mutex::new(Vec::new()),
            partial_text: Mutex::new(String::new()),
            api_key,
        }
    }

    pub fn process_audio(&self, audio_data: &[u8]) -> Result<(), OmniMemError> {
        let mut buffer = self.audio_buffer.lock().unwrap();
        buffer.extend_from_slice(audio_data);

        let mut text = self.partial_text.lock().unwrap();
        *text = format!("Listening... ({}s)", buffer.len() / (16000 * 2));
        Ok(())
    }

    pub fn get_partial_transcription(&self) -> String {
        let text = self.partial_text.lock().unwrap();
        text.clone()
    }

    pub fn stop_and_summarize(&self) -> Result<String, OmniMemError> {
        let mut buffer = self.audio_buffer.lock().unwrap();
        if buffer.is_empty() {
             return Ok("No audio recorded".to_string());
        }

        let pcm_len = buffer.len() as u32;
        let mut wav_data = create_wav_header(pcm_len, 16000, 1, 16);
        wav_data.extend_from_slice(&buffer);
        
        // On vide le buffer pour la suite
        buffer.clear();
        drop(buffer);

        let client = Client::new();
        
        // 1. Transcription via Whisper (Groq)
        let form = multipart::Form::new()
            .part("file", multipart::Part::bytes(wav_data).file_name("audio.wav").mime_str("audio/wav").unwrap())
            .text("model", "whisper-large-v3-turbo")
            .text("language", "fr")
            .text("response_format", "json");

        let response = client.post("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", format!("Bearer {}", self.api_key))
            .multipart(form)
            .send()
            .map_err(|_| OmniMemError::NetworkError)?;

        let transcription_json: Value = response.json().map_err(|_| OmniMemError::InternalError)?;
        let transcript = transcription_json["text"].as_str().unwrap_or("").to_string();

        if transcript.is_empty() {
            return Ok("Transcription vide".to_string());
        }

        // 2. Résumé via Llama 3 (Groq)
        let prompt = format!("Tu es un assistant de prise de notes. Résume de manière concise sous forme de liste à puces le texte suivant récupéré par reconnaissance vocale :\n\n{}", transcript);
        
        let body = serde_json::json!({
            "model": "llama3-8b-8192",
            "messages": [
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.5
        });

        let response = client.post("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", format!("Bearer {}", self.api_key))
            .json(&body)
            .send()
            .map_err(|_| OmniMemError::NetworkError)?;

        let result_json: Value = response.json().map_err(|_| OmniMemError::InternalError)?;
        let summary = result_json["choices"][0]["message"]["content"]
            .as_str()
            .unwrap_or("Erreur lors de la génération du résumé")
            .to_string();

        Ok(summary)
    }
}
