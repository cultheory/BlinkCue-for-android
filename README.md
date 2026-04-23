# BlinkCue

BlinkCue is an Android app designed to reduce eye strain and dryness by encouraging natural blinking.  
If no blink is detected for a certain period, the screen is covered with a black overlay to prompt a break.

---

## ✨ Features

- 📷 Real-time blink detection using front camera (ML Kit Face Detection)
- ⏱ Full-screen black overlay when no blink is detected for a set time
- 👁 Instant overlay removal when a blink is detected
- 🔔 Runs in background using Foreground Service
- ⚙️ Simple UI (timeout setting / Start / Stop)

---

## 🧠 How it works

1. Tap **Start**
   - Checks camera and overlay permissions
   - Starts a foreground service

2. Blink detection
   - Tracks eye state in real time
   - Records last blink timestamp

3. No blink for configured time
   → Shows full-screen black overlay (`Blink!`)

4. Blink detected
   → Overlay disappears immediately

5. Tap **Stop**
   → Stops service and removes overlay

---

## ⚠️ Limitations (Android Policy)

Android 14 / 15 impose strict limitations on background camera usage.

- Camera access is only allowed in a Foreground Service
- Camera may stop working if the app is not visible
- Background behavior may vary by device/OEM
- Overlay behavior may differ depending on system UI/navigation

> Full unrestricted background camera usage is not guaranteed due to Android security policies.

---

## 🎯 Target Devices

- Android 14 / 15
- Tested primarily on Samsung Galaxy S24 / S25

---

## 🛠 Tech Stack

- Kotlin
- CameraX (ImageAnalysis)
- ML Kit Face Detection
- Foreground Service
- System Overlay (`TYPE_APPLICATION_OVERLAY`)

---

## 🚀 How to Run

1. Install APK  
2. Grant permissions  
   - Camera  
   - Display over other apps  

3. Launch app  
   - Set timeout  
   - Tap Start  

---

## 📦 Build

```bash
./gradlew assembleRelease