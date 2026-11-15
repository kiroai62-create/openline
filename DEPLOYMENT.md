# Deployment guide: AI receptionist for clients

Is guide mein shuru se aakhir tak ye sab hai: server lagana, phone app banana, har client ka
phone set karna, WhatsApp jodna, aur roz ka kaam. Commands jaise likhe hain waise hi chalayein.

**Ek baar ka kaam (aapke liye):** Step 1 se 5.
**Har naye client ke liye:** Step 6 se 9.

---

## 0. Kya kya chahiye

**Aapke liye (ek baar):**

| Cheez | Kyun | Note |
|---|---|---|
| Ek Linux VPS (Ubuntu 24.04, 1 vCPU, 2 GB RAM) | Server 24 ghante chalega | Hostinger, DigitalOcean, AWS Lightsail, koi bhi |
| Ek domain ya subdomain, jaise `voice.aapkibrand.com` | Phone aur console isi address se server tak pahunchte hain | HTTPS zaroori hai |
| Gemini API key | AI ki awaaz aur dimaag | aistudio.google.com se. Billing on karna padega |
| GitHub account | APK banane ke liye | Ye repo isi mein hai |

**Har client ke liye:**

| Cheez | Note |
|---|---|
| Ek Android phone, Android 11 ya naya | Abhi tak sirf **Vivo V40 (Android 16)** pe poori tarah test hua hai. Doosre phone pe pehle test call zaroor karein |
| Ek SIM jisme calling ho | Isi number pe AI call uthayega |
| Charger aur 24 ghante bijli | Phone hamesha charging pe rahe |
| Internet: Wi-Fi ya mobile data | Pehli baar setup aur har reboot ke baad Wi-Fi zaroori hai, baad mein mobile data bhi chalega |
| Client ka WhatsApp gateway | Jaise 32 Teeth wala Hugging Face Space. Har client ka apna WhatsApp number aur apna Space |

> **Ek SIM pe ek waqt mein ek hi call.** Agar client ke yahan ek saath kai calls aati hain, to
> utne phone aur SIM lagayein. Ek client ko kai phone assign kar sakte hain.

---

## 1. Server lagana (VPS pe)

VPS mein SSH se login karein, phir ye commands chalayein.

### 1.1 Node.js 22 aur git

```bash
sudo apt update && sudo apt install -y git curl
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
node -v        # v22.13 ya usse naya hona chahiye
```

### 1.2 Code download aur build

```bash
sudo mkdir -p /opt/receptionist && sudo chown $USER /opt/receptionist
git clone https://github.com/kiroai62-create/openline.git /opt/receptionist
cd /opt/receptionist/server
npm ci
npm run build
```

### 1.3 `.env` file

```bash
cp .env.example .env
openssl rand -base64 36      # ye ek lamba random key banata hai, copy karein
nano .env
```

`.env` mein ye badlein:

```ini
PORT=8787
HOST=127.0.0.1                                   # server sirf Caddy ke peeche rahe
HTTP_GATEWAY_API_KEY=<upar wala random key>      # kisi ko mat dena
PUBLIC_URL=https://voice.aapkibrand.com
DATA_DIR=/opt/receptionist/server/data
GEMINI_API_KEY=<aapki Gemini key>                # ya baad mein console Settings mein daalein
```

Save karne ke liye `Ctrl+O`, `Enter`, phir `Ctrl+X`.

### 1.4 Server ko hamesha chalta rakhna (systemd)

```bash
sudo tee /etc/systemd/system/receptionist.service > /dev/null <<EOF
[Unit]
Description=AI receptionist server
After=network-online.target

[Service]
WorkingDirectory=/opt/receptionist/server
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=3
User=$USER

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now receptionist
sudo systemctl status receptionist     # "active (running)" dikhna chahiye
```

### 1.5 HTTPS (Caddy)

Pehle domain ke DNS mein ek **A record** banayein: `voice.aapkibrand.com` → VPS ka IP.
Phir:

```bash
sudo apt install -y caddy
sudo tee /etc/caddy/Caddyfile > /dev/null <<'EOF'
voice.aapkibrand.com {
    reverse_proxy 127.0.0.1:8787
}
EOF
sudo systemctl reload caddy
```

Caddy khud SSL certificate le leta hai, aur WebSocket bhi isi se chalta hai. VPS ke firewall mein
port 80 aur 443 khule hone chahiye (`sudo ufw allow 80,443/tcp`). Port 8787 bahar mat kholein.

> **VPS nahi hai?** Ghar ke PC pe bhi chala sakte hain. Tab HTTPS ke liye Tailscale Funnel
> (`tailscale funnel --bg 8787`) ya Cloudflare Tunnel use karein. Windows pe `run-server.bat`
> double-click karein. Lekin PC 24 ghante on rehna chahiye.

---

## 2. Console mein pehla login

1. Browser mein `https://voice.aapkibrand.com/` kholein.
2. Email aur password se account banayein. Kyunki aap internet ke through khol rahe hain, ye
   **setup key** maangega: `.env` wala `HTTP_GATEWAY_API_KEY` paste karein.
3. **Settings → Server**:
   - **Public address**: `https://voice.aapkibrand.com`
   - **Product name**: aapki brand ka naam. Ye console ke upar dikhega.
4. **Settings → AI assistant**: Gemini key daalein (agar `.env` mein nahi daali) aur Save karein.

---

## 3. Phone app (APK) banana

Android Studio ki zaroorat nahi hai. GitHub khud APK bana deta hai.

1. GitHub pe repo kholein → **Actions** tab → **Android APK** → **Run workflow**.
2. **App name** mein wo naam likhein jo client ke phone pe dikhna chahiye (jaise aapki brand),
   phir **Run workflow** dabayein.
3. 5-10 minute baad run hara (✓) ho jaayega. Use kholein, neeche **Artifacts** mein
   `phone-app-apk` download karein. Zip ke andar `app-debug.apk` hai.

Ek hi APK saare clients ke phone mein chalega.

---

## 4. Server update karna (jab bhi naya code aaye)

```bash
cd /opt/receptionist && git pull
cd server && npm ci && npm run build
sudo systemctl restart receptionist
```

Restart ke dauran chal rahi call kat jaayegi, isliye update raat mein karein.

## 5. Backup

Saara data ek file mein hai: `/opt/receptionist/server/data/gateway.db`. Isme clients, calls,
transcripts aur settings sab hain. Roz backup lene ke liye:

```bash
sudo apt install -y sqlite3
mkdir -p ~/backups
( crontab -l 2>/dev/null; echo '0 3 * * * sqlite3 /opt/receptionist/server/data/gateway.db ".backup '"$HOME"'/backups/gateway-$(date +\%F).db"' ) | crontab -
crontab -l     # roz raat 3 baje wali line dikhni chahiye
```

---

## 6. Naye client ka phone set karna

Ye kaam client ke yahan, phone haath mein lekar karein. Phone Wi-Fi se juda ho.

### 6.1 App install

1. APK phone mein bhejein (WhatsApp, USB ya Google Drive se) aur kholein.
2. "Unknown apps install karne ki ijazat" maange to allow karein.

### 6.2 Default phone app

1. App kholein → step 1 **Default phone app** → **Set** → app chunein.
2. **Vivo, Oppo, Xiaomi:** agar "Restricted setting" ka message aaye, to
   **Settings → Apps → (app) → ⋮ (upar right) → Allow restricted settings** karein, phir dobara
   try karein.

### 6.3 Server se jodna (QR)

1. Laptop pe console mein **Connection** page kholein. Wahan QR code dikhega.
2. Phone mein step 2 → **Scan QR** → QR scan karein.
3. Console mein phone **Online** dikhne lagega.
4. Phone ka naam pehchaan ke liye yaad rakhein (jaise "Vivo V2348"). Client banate waqt yahi
   chunna hai.

### 6.4 Call audio (ek baar ki pairing)

1. **Developer options** chalu karein: **Settings → About phone → Software version / Build
   number** pe 7 baar tap karein.
2. App mein step 3 **Call audio → Set up** dabayein. Notification ki ijazat maange to dein.
3. App aapko **Wireless debugging** screen pe le jaayega. Use **on** karein.
4. **Pair device with pairing code** dabayein. Ek 6-digit code dikhega. Is screen ko band mat
   karein.
5. Upar se notification panel kheenchein. Wahan app ki notification mein **Enter code** dabayein,
   code type karke bhejein.
6. "Call audio ready" aaye to ho gaya. App mein step 3 hara ho jaayega.

### 6.5 Background mein chalta rehna

1. App mein step 4 → **Allow** (battery optimisation band).
2. **Vivo:** **Settings → Battery → Background power consumption → (app) → Allow**.
   Doosre brands mein "Autostart" aur "No restrictions" chalu karein.
3. Phone ko charger pe lagakar ek jagah rakh dein. Screen lock rakh sakte hain.

---

## 7. Client ka WhatsApp gateway

Har client ka apna WhatsApp number hona chahiye, taaki booking link usi clinic ke number se jaaye
aur patient wahin reply kare.

1. 32 Teeth wale gateway (Hugging Face Space + PHP dashboard) ki ek copy client ke liye banayein.
2. Space ke secrets mein `PHP_URL` aur `API_SECRET` set karein, aur client ke WhatsApp se QR scan
   karein.
3. Space ka address (jaise `https://client-name.hf.space`) aur `API_SECRET` note kar lein.

> Ye gateway WhatsApp Web (Baileys) pe chalta hai, jo WhatsApp ki official API nahi hai.
> Bahut zyada ya bina maange messages bhejne pe WhatsApp number block kar sakta hai. Yahan message
> sirf usi ko jaata hai jisne khud call karke booking maangi, phir bhi client ko ye baat bata dein.

---

## 8. Console mein client banana

1. **Clients → New client**.
2. Bharein:
   - **Business name**: jaise "32 Teeth Hospital". AI isi naam se call uthayega.
   - **Phones**: step 6.3 wala phone tick karein.
   - **Voice**: Kore (female) ya Puck (male).
   - **Booking link**: client ki website ka booking page, ya `https://wa.me/91XXXXXXXXXX`.
   - **Everything the receptionist should know**: client ki saari jaankari. Website ka text,
     timings, doctors kab baithte hain, fees policy, emergency number, FAQs. Example:
     [`clients/32-teeth-hospital.md`](clients/32-teeth-hospital.md).
   - **WhatsApp**: step 7 ka gateway address, API secret, country code `91`.
3. **Save** dabayein, phir **Send test** se apne number pe test message mangwayein.
4. Upar switch **Answer this client's calls** on rahe.

Bas. Ab us phone pe aane wali har call AI uthayega.

> Jaankari jitni poori hogi, AI utna achha jawab dega. Jo baat text mein nahi hai, us pe AI andaza
> nahi lagata. Wo bolta hai "staff confirm karega" aur message le leta hai.

---

## 9. Test aur handover

Kisi doosre phone se client ke SIM number pe call karein aur ye check karein:

- [ ] AI 1-2 second mein uthata hai aur business ka naam leke greeting deta hai
- [ ] Gateway phone pe koi awaaz nahi aati (wo chup rehta hai)
- [ ] Timings, address, doctor ke baare mein sahi jawab aata hai
- [ ] "Appointment chahiye" bolne pe AI number confirm karke WhatsApp pe link bhejta hai
- [ ] WhatsApp pe message aata hai, aur reply karne pe client ka WhatsApp AI jawab deta hai
- [ ] Console **Playground** mein call, transcript aur message dikhte hain

### Client ka purana number

Client chahe to apna purana clinic number na badle. Purane number se is SIM pe **call forwarding**
laga dein: purane phone mein **Settings → Calls → Call forwarding**. Jab clinic ka staff busy ho ya
call na uthaye, tab forward karein, ya hamesha. Forwarding ka charge SIM ke plan pe depend karta
hai.

---

## Roz ka kaam aur problem aaye to

| Dikkat | Kya karein |
|---|---|
| Console mein phone **Offline** | Phone ka internet check karein. App ek baar kholein |
| **Call audio: Stopped** (phone reboot ke baad hota hai) | Phone ko Wi-Fi se jodkar app kholein, helper khud start ho jaata hai. Uske baad mobile data bhi chalega |
| AI uthata hai par awaaz nahi aati | App mein step 3 dekhein. Ye phone model support na karta ho, aisa bhi ho sakta hai |
| WhatsApp link nahi gaya | Clients page pe **Send test** karein. Error dikhega (secret galat, gateway band, ya WhatsApp disconnect) |
| AI galat jaankari deta hai | Client ka "Everything the receptionist should know" text theek karein. Agli call se lagu hoga |
| Server ke logs dekhne hain | `sudo journalctl -u receptionist -n 200 -f` |

## Kharcha

- **Gemini Live:** har call ke minute ke hisaab se Google charge karta hai. Rate
  [Gemini API pricing](https://ai.google.dev/pricing) pe dekhein, aur client ka plan usi hisaab se
  rakhein.
- **VPS:** ek hi server pe kai clients chal jaate hain.
- **SIM:** client ka normal plan. Incoming calls aam taur pe free hoti hain.

## Kanoon aur bharosa

- AI hamesha khud ko virtual assistant batata hai.
- Call ki recording save nahi hoti. Sirf text transcript aapke server pe save hota hai.
- AI na bimari batata hai na dawai. Emergency mein patient ko turant aane ya emergency number pe
  call karne ko kehta hai.
- Ye software MIT license ka hai. Apni brand se bech sakte hain, bas `LICENSE` file code ke saath
  rehni chahiye.
