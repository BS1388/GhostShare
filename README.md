<p align="center">
  <img src="Logo/logo.png" width="128" alt="GhostShare Logo" />
</p>

<p align="center">
  <b>👻 GhostShare</b><br />
  <strong>System-level privacy shield that strips tracking parameters from copied links in real-time.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%209%2B-brightgreen?style=flat-square" alt="Android" />
  <img src="https://img.shields.io/badge/Framework-LSPosed-blue?style=flat-square" alt="LSPosed" />
  <img src="https://img.shields.io/badge/UI-Material%203%20Dynamic%20Colors-purple?style=flat-square" alt="Material 3" />
  <img src="https://img.shields.io/badge/License-GPLv3-orange?style=flat-square" alt="License" />
</p>

---

**✨ Overview**

**GhostShare** is an ultra-lightweight, battery-friendly LSPosed module hooked directly into `system_server`. It automatically monitors clipboard operations and cleans telemetry, affiliate, and analytical tracking tags from URLs without breaking essential parameters or requiring root background services.

**🚀 Key Features**

* **Zero Overhead ($O(N)$ fast-path):** Bypasses non-URL clips instantly; zero battery drain.
* **Deep System Hook:** Runs securely inside `system_server` with zero memory leaks (`ThreadLocal` sanitation).
* **Silent & Non-intrusive:** 5-second silent notification with an instant **Undo** button.
* **Preserves Media States:** Retains crucial parameters like YouTube timestamps (`t=`, `start=`) and playlist indexes.
* **Material You (Dynamic Colors):** Follows system palette, dark/light themes, and edge-to-edge aesthetics.
* **Per-app settings:** *Enable per-app* cleans links only for the apps you choose, *Bypass Mode* never touches links copied from the apps you choose.
* **My Parameters:** the tracking-parameter list is data, not code. See, add and remove parameters (a trailing `*` matches a prefix) or restore the defaults.
* **Excluded websites:** links from the websites you add (subdomains included) are never changed.
* **Update lists:** an update button in My Parameters and Excluded websites fetches the latest lists from GhostShare, AdGuard and ClearURLs, or from a link you add yourself (parameters you removed stay removed). Sources can be reordered, and the order is remembered. The network is used only when you tap a source.
* **Site rules:** per-website rules to always keep or additionally remove specific parameters (for example keep `t` on one site, or remove `foo` on another). Rules cover the site and its subdomains, and a keep rule always wins.
* **Link test:** paste a link inside the app to see the cleaned result and exactly which parameters were removed, using your current settings.
* **Quick Settings tile:** add the Quick Restore Notification tile to your Quick Settings panel to turn the 5-second restore notification on or off without opening the app.
* **JSON backup:** export and import your parameters and exceptions through the system file picker, with overwrite or merge on import.
* **Redirect links:** unwraps wrapper links (`l.facebook.com`, `google.com/url`, `youtube.com/redirect`, `out.reddit.com`, ...) to the real destination.
* **Live changes:** every change is applied immediately, no reboot needed (after the first reboot that loads the updated module).

**🌐 Supported Platforms**

* **Instagram:** every query parameter is removed, except the functional ones (`img_index`, `story_media_id`, `next`, `q`)
* **YouTube & YouTube Music:** `si`, `feature`, timestamp preserved
* **X (Twitter):** `s`, `t`, `ref_src`
* **TikTok & Google Analytics:** `_r`, `_t`, `gclid`, `fbclid`
* **Reddit & Pinterest**
* **Spotify & SoundCloud**

**📥 Installation**

1. Install [LSPosed Framework](https://github.com/LSPosed/LSPosed).
2. Download and install the latest `GhostShare.apk` from [Releases](https://github.com/BS1388/GhostShare/releases).
3. Enable **GhostShare** in LSPosed Manager (Target: `System Framework` / `system_server`).
4. Reboot your phone or soft-restart the framework.

---

**📄 License**

Copyright (C) 2026 [BS1388](https://github.com/BS1388). Licensed under the GNU GPLv3.

---

Developed by [BS1388](https://github.com/BS1388)
