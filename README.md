# 👻 GhostShare

<p align="center">

<img src="Logo/logo.png" width="128" alt="GhostShare Logo" />
</p>
  
  <strong>System-level privacy shield that strips tracking parameters from copied links in real-time.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.1%2B-brightgreen?style=flat-square" alt="Android" />
  <img src="https://img.shields.io/badge/Framework-LSPosed-blue?style=flat-square" alt="LSPosed" />
  <img src="https://img.shields.io/badge/UI-Material%203%20Dynamic%20Colors-purple?style=flat-square" alt="Material 3" />
  <img src="https://img.shields.io/badge/License-GPLv3-orange?style=flat-square" alt="License" />
</p>

---

## ✨ Overview
**GhostShare** is an ultra-lightweight, battery-friendly LSPosed module hooked directly into `system_server`. It automatically monitors clipboard operations and cleans telemetry, affiliate, and analytical tracking tags from URLs without breaking essential parameters or requiring root background services.

## 🚀 Key Features
* **Zero Overhead ($O(N)$ fast-path):** Bypasses non-URL clips instantly; zero battery drain and immune to ReDoS.
* **Deep System Hook:** Runs securely inside `system_server` with zero memory leaks (`ThreadLocal` sanitation).
* **Silent & Non-intrusive:** 5-second silent notification with an instant **Undo** button.
* **Preserves Media States:** Retains crucial parameters like YouTube timestamps (`t=`, `start=`) and playlist indexes.
* **Material You (Dynamic Colors):** Follows system palette, dark/light themes, and edge-to-edge aesthetics.

## 🌐 Supported Platforms
* **Instagram & Threads** (`igsh`, `utm_*`)
* **YouTube & YouTube Music** (`si`, `feature`, timestamp preserved)
* **X (Twitter)** (`s`, `t`, `ref_src`)
* **TikTok & Google Analytics** (`_r`, `_t`, `gclid`, `fbclid`)
* **Reddit & Pinterest**
* **Spotify & SoundCloud**

## 📥 Installation
1. Install [LSPosed Framework](https://github.com/LSPosed/LSPosed).
2. Download and install the latest `GhostShare.apk` from [Releases](https://github.com/BS1388/GhostShare/releases).
3. Enable **GhostShare** in LSPosed Manager (Target: `System Framework` / `system_server`).
4. Reboot your phone or soft-restart framework.

---
Developed with ❤️ by [BS1388](https://github.com/BS1388)
