# Deployment guide: ek client, ek alag deployment

Har client ka **apna alag deployment** hota hai: apna code, apna database, apni settings, apni AI
keys, apna WhatsApp, apna web address, apna console login aur apna phone app. Ek client ki calls,
data ya API limit ka doosre client pe koi asar nahi padta.

```
            Ek free server (Oracle Cloud)              ya har client ka alag server
 ┌────────────────────────────────────────────┐
 │ receptionist-32teeth   → 32teeth.duckdns.org │   32teeth  → apna server, apna address
 │ receptionist-cityeye   → cityeye.duckdns.org │   cityeye  → apna server, apna address
 │ receptionist-...                             │
 └────────────────────────────────────────────┘
 Har ek: alag folder, alag database, alag .env (alag Gemini/Groq/Sarvam keys), alag service
```

> **API limit ka asli hisaab:** AI ki limit **API key aur uske account** pe lagti hai, server pe
> nahi. Isliye har client ke deployment mein **usi client ke apne account** (client ka apna Google
> account) ki keys daalein. Tab ek client ki zyada calls se doosre client ki limit kabhi khatam
> nahi hogi. Ek hi aadmi ke dher saare nakli account banakar free limit badhana providers ki terms
> ke khilaaf ho sakta hai aur account band ho sakta hai, isliye har client ka apna asli account
> rakhein.

**Ek baar ka kaam (aapke liye):** Step 1 aur 2.
**Har naye client ke liye:** Step 3 se 10.

---

## 0. Kya kya chahiye

| Cheez | Note |
|---|---|
| Ek server | Free option neeche hai (Step 1) |
| Har client ka address | Free: DuckDNS se `clientname.duckdns.org`. Ya apna domain: `32teeth.aapkibrand.com` |
| Har client ki AI keys | Client ke apne Google account se Gemini key, aur backup ke liye Groq / Sarvam / Cloudflare (Step 6) |
| Har client ka Android phone | Android 11 ya naya, SIM ke saath, 24 ghante charging pe. Abhi tak poori tarah sirf **Vivo V40 (Android 16)** pe test hua hai, isliye doosre phone pe pehle test call zaroor karein |
| Har client ka WhatsApp gateway | Jaise 32 Teeth wala Hugging Face Space (Step 7) |
| GitHub account | App (APK) banane ke liye |

> **Ek SIM pe ek waqt mein ek hi call.** Kisi client ke yahan ek saath kai calls aati hain to
> utne phone aur SIM uske deployment se jodein (sab ek hi console mein aate hain).

---

## 1. Free server: kaunsa platform

**Client ko Oracle ya kisi cloud ka account banane ki zaroorat nahi hai.** Server **aapke** account
pe chalta hai. Ek hi VM pe har client ka alag deployment hota hai: alag folder, alag database, alag
service, alag address. API limit server se nahi, **API key** se judi hai, aur wo keys client ke
apne Google, Groq ya Sarvam account ki hoti hain (Step 6). Isliye ek aapka Oracle VM saare clients
ke liye kaafi hai.

Agar Oracle mein VM na mile ("out of capacity"), ya kisi client ko alag server chahiye, to ye
options hain:

| Platform | Kitne din free | Machine | India server? | Kai clients? | Note |
|---|---|---|---|---|---|
| **Oracle Cloud Always Free** | Hamesha | Arm: kul 2 CPU, 12 GB RAM | Haan (Mumbai, Hyderabad) | 15-20 | **Pehli pasand.** Card verify hota hai, charge nahi |
| **Microsoft Azure free account** | **12 mahine** | B1s / B2ats v2 / B2pts v2, 750 ghante/mahina (1 VM 24×7) | Haan (Central India, Pune) | 3-5 | **Doosri pasand, 1 saal ke liye.** 30 din baad "pay as you go" mein upgrade karna padta hai, tabhi 12 mahine ka free chalta hai. Free limit se bahar gaye to bill banta hai |
| **Google Cloud e2-micro** | Hamesha | 2 chhote vCPU, 1 GB RAM | Nahi, sirf US (us-central1, us-west1, us-east1) | 1-2 | Hamesha free, lekin US ki wajah se har jawab mein ~0.25 s zyada delay. Sirf **1 GB/mahina** bahar jaane wala data free, uske baad kuch paise lagte hain (neeche dekhein) |
| Clinic ka apna PC / purana laptop + Tailscale Funnel | Hamesha | Jo bhi ho | Clinic mein | 1 | Free hai, lekin PC, bijli aur internet 24 ghante chalu rehne chahiye |
| AWS | Sirf 6 mahine ($100-200 credit) | | Haan | | 1 saal nahi chalega. July 2025 se naye accounts ka 12 mahine wala free khatam ho gaya |
| Render / Koyeb / Hugging Face | Free, lekin so jaate hain | | | Nahi | **Mat lein.** Soya hua server call nahi uthata, aur data mit jaata hai |

**Data ka hisaab:** ek minute ki call mein server se bahar lagbhag 3-4 MB data jaata hai (phone ko
AI ki awaaz, aur AI ko caller ki awaaz). Matlab 1 GB mein lagbhag 250-300 minute.
- Oracle mein mahine ka 10 TB free hai, isliye wahan fikr ki baat nahi.
- Google Cloud mein 1 GB ke baad lagbhag $0.12 (₹10) per GB lagta hai. Har 1000 minute pe kuch
  dollar.
- Azure ka free data limit signup ke waqt unki pricing page pe dekh lein.

**Budget plan (agar free na chale):** ek sasta VPS (Hostinger, Hetzner, DigitalOcean, ₹400-800
mahina) lein aur usi pe saare clients chalayein. Scripts bilkul waise hi chalti hain.

Ye jaankari November 2026 ki hai. Free plans badalte rehte hain, signup se pehle provider ki site
pe dekh lein.

**Server ki jagah:** India ka region chunein (Oracle: Mumbai ya Hyderabad, Azure: Central India).
Call ki awaaz server se aati-jaati hai, to paas wala server matlab kam delay.

### Azure account ke liye kya chahiye

- **Microsoft account** (Outlook/Hotmail email) ya GitHub account
- **Mobile number**, SMS/call se verification ke liye
- **Visa ya Mastercard** credit ya debit card, aapke apne naam ka. **RuPay, prepaid aur virtual
  card nahi chalte.** Card pe **international/online transactions on** hone chahiye (bank ke app
  mein). Verification ke liye lagbhag ₹2 kat ke wapas aa jaate hain.
- Bank autopay (e-mandate) ki permission maang sakta hai, aur usme bada amount dikh sakta hai. Ye
  sirf limit hai, free limit ke andar rehne pe paisa nahi katta.
- Ek aadmi ka ek hi free account banta hai. Jisne pehle Azure free trial liya ho, use dobara nahi
  milta.
- Signup ke baad **Cost Management → Budgets** mein ₹100 ka budget alert laga dein, taaki galti se
  koi paid cheez chal jaaye to email aa jaaye.
- 30 din baad **Upgrade to pay-as-you-go** karein, tabhi 12 mahine ka free VM chalta rehta hai.
  Upgrade na kiya to account band ho jaata hai.

### Azure ya Google Cloud pe VM

`setup-vm.sh` aur `new-client.sh` kisi bhi **Ubuntu 22.04/24.04** VM pe chalti hain.

- **Azure:** portal.azure.com → **Virtual machines → Create**. Region: Central India. Image: Ubuntu
  24.04. Size: free account wala (B1s ya B2ats v2, "Free services eligible" likha hota hai). Disk:
  Standard SSD (64 GB tak free). **Inbound ports:** SSH, HTTP, HTTPS chunein.
- **Google Cloud:** console.cloud.google.com → **Compute Engine → Create instance**. Region:
  us-central1. Machine: **e2-micro**. Boot disk: Ubuntu 24.04, **Standard persistent disk 30 GB**.
  **Allow HTTP** aur **Allow HTTPS traffic** tick karein. RAM 1 GB hai, isliye install se pehle swap
  bana lein:
  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```

Uske baad Step 2 se aage sab waise hi karein.

### 1.1 Oracle Cloud pe VM banana

1. cloud.oracle.com pe Free Tier account banayein. Home region Mumbai ya Hyderabad rakhein, kyunki
   baad mein badal nahi sakte.
2. **Compute → Instances → Create instance**:
   - Image: **Canonical Ubuntu 24.04**
   - Shape: **Ampere VM.Standard.A1.Flex**, 2 OCPU, 12 GB (Always Free eligible)
   - **Add SSH keys**: "Generate a key pair" chunein aur private key download kar lein
   - Create dabayein
3. **Ports kholna:** Instance page → **Subnet** → **Security List** (Default) → **Add Ingress
   Rules**. Source `0.0.0.0/0`, TCP, destination port **80** ka ek rule aur **443** ka ek rule.
4. Instance ka **Public IP** note kar lein.
5. SSH se login karein:
   ```bash
   ssh -i ssh-key.key ubuntu@<PUBLIC_IP>
   ```

> "Out of capacity" error aaye to kuch ghante baad dobara try karein, ya doosra Availability
> Domain chunein.

---

## 2. Server ki ek baar ki setup

VM mein ye ek command chalayein. Ye Node.js 22, Caddy (HTTPS), sqlite3 install karti hai, ports
80/443 kholti hai, aur saare clients ka roz raat 3 baje backup set karti hai:

```bash
curl -fsSL https://raw.githubusercontent.com/kiroai62-create/openline/main/deploy/setup-vm.sh | sudo bash
```

Uske baad scripts ke liye code ek baar download kar lein:

```bash
git clone https://github.com/kiroai62-create/openline.git ~/openline
```

---

## 3. Client ka web address

**Free (DuckDNS):**
1. duckdns.org pe Google se login karein.
2. Subdomain mein client ka naam daalein, jaise `32teeth`, aur **add domain** dabayein.
3. **current ip** mein VM ka Public IP daalein aur **update ip** dabayein.
4. Address taiyaar hai: `32teeth.duckdns.org`. Ek DuckDNS account mein 5 subdomain bante hain.

**Apna domain ho to:** DNS mein **A record** banayein: `32teeth.aapkibrand.com` → VM ka IP.

---

## 4. Client ka deployment banana

```bash
cd ~/openline
sudo bash deploy/new-client.sh 32teeth "32 Teeth Hospital" 32teeth.duckdns.org https://wa.me/918789354445
```

| Hissa | Matlab |
|---|---|
| `32teeth` | Chhota naam: chhote akshar, ank aur `-` |
| `"32 Teeth Hospital"` | Business ka naam. AI isi naam se call uthayega |
| `32teeth.duckdns.org` | Step 3 wala address |
| `https://wa.me/...` | Booking link (chahein to baad mein badal sakte hain) |

Script ye sab karti hai:
- `/opt/receptionist/clients/32teeth/` mein code ki **alag copy** banati hai
- Us client ki alag `.env` banati hai, ek naye random setup key ke saath
- Alag database: `/opt/receptionist/clients/32teeth/server/data/gateway.db`
- Alag service `receptionist-32teeth` chalati hai, jo crash ya reboot ke baad khud chalu ho jaati hai
- HTTPS address set karti hai
- Receptionist ko pehle se is client ke naam, booking link aur auto-answer ke saath set kar deti hai

Aakhir mein script **Setup key** dikhati hai. Use sambhal kar rakhein.

Saare clients dekhne ke liye:
```bash
bash deploy/list-clients.sh
```

---

## 5. Client ke console mein pehla login

1. `https://32teeth.duckdns.org/` kholein. Pehli baar HTTPS certificate banne mein ek minute lag
   sakta hai.
2. Email aur password se account banayein, aur **Setup key** (Step 4 wali) paste karein.
3. Upar sirf isi client ka naam dikhega. Ye console aur iska data sirf is client ka hai.
4. Chahein to client ko bhi uska apna login de sakte hain: **Settings → People with access**.

---

## 6. Client ki AI keys aur failover

**Settings → AI assistant:** client ke apne Google account ki **Gemini API key**
(aistudio.google.com/apikey).

**Settings → AI providers & failover:** backup providers jodein. Jab ek provider ki limit khatam ho
ya wo fail ho, agla apne aap chal padta hai, call ke beech mein bhi.

1. Pehle **Gemini Live** chalta hai: Settings wali key, phir backup Gemini keys, order se.
2. Saari Live keys limit pe hon, to **speech pipeline** chalti hai: awaaz → text → jawab → awaaz.
   Har step ki apni list hai.
3. Jo provider limit, quota, galat key ya server error de, wo kuch der ke liye bahar ho jaata hai:
   limit pe 90 second (din ki limit ho to 1 ghanta), galat key pe 10 minute, baaki error pe 20
   second.

| Provider | Kis kaam aata hai | Key |
|---|---|---|
| Google Gemini | Live voice, chat model, text to speech | aistudio.google.com/apikey |
| Groq | Chat model (bahut tez), speech to text, English text to speech | console.groq.com/keys |
| Cloudflare Workers AI | Chat model, speech to text (**Account ID** bhi chahiye) | dash.cloudflare.com → API Tokens |
| OpenRouter | Chat model (`:free` wale models) | openrouter.ai/keys |
| Cerebras | Chat model | cloud.cerebras.ai |
| Mistral | Chat model, speech to text | console.mistral.ai |
| Sarvam AI | **Hindi** speech to text aur text to speech | dashboard.sarvam.ai |
| OpenAI-compatible | Koi bhi aur OpenAI jaisi API (base URL daalein) | |

**Hindi ke liye suggested order:**
- Gemini Live: client ki key, phir ek backup Gemini key
- Chat model: Groq → Cerebras → Cloudflare → OpenRouter
- Speech to text: Sarvam → Groq
- Text to speech: Sarvam → Gemini

Speech pipeline har jawab mein 1.5-3 second leti hai, jo Gemini Live se dheema hai, isliye ise
backup ki tarah rakhein. Model ke naam badalte rehte hain, har provider mein model ka naam badal
sakte hain.

### Call kabhi chup-chaap nahi kategi

**Settings → If the AI can't speak** mein do message hain. Dono pehle se record karke database mein
rakhe jaate hain, isliye saare AI band hon tab bhi bajte hain:

- **While switching** ("एक पल, कृपया लाइन पर बने रहिए।"): jab call ke beech AI badalta hai.
  Ek call mein ek hi baar bajta hai.
- **When nothing works** ("माफ़ कीजिए... कृपया थोड़ी देर बाद कॉल करें"): jab koi bhi AI kaam na kare.
  Ye message bajne ke baad hi call katti hai.

Ye message Gemini key ya kisi text-to-speech provider se record hote hain. **Save and record**
dabane ke baad "Recorded" dikhna chahiye. Record na hue hon to unki jagah beep bajti hai.

---

## 7. Client ka WhatsApp

Har client ka apna WhatsApp number aur apna gateway hona chahiye, taaki booking link usi clinic ke
number se jaaye aur patient wahin reply kare.

1. 32 Teeth wale gateway (Hugging Face Space + PHP dashboard) ki ek copy client ke liye banayein.
2. Space ke secrets mein `PHP_URL` aur `API_SECRET` set karein, phir client ke WhatsApp se QR scan
   karein.
3. Client ke console mein **Settings → WhatsApp**: Space ka address aur `API_SECRET` daalein, phir
   **Send test** dabayein.

> Ye gateway WhatsApp Web (Baileys) pe chalta hai, jo WhatsApp ki official API nahi hai. Bahut
> zyada ya bina maange messages bhejne pe number block ho sakta hai. Yahan message sirf usi ko
> jaata hai jisne khud booking maangi, phir bhi client ko ye baat bata dein.

---

## 8. Client ki jaankari

Console mein **Playground → Assistant**:
- **Role:** Receptionist (pehle se set hai)
- **Business name** aur **Booking link** (pehle se set hain, badal sakte hain)
- **Everything the receptionist should know:** client ki saari jaankari, jaise website ka text,
  timings, doctors kab baithte hain, fees policy, emergency number, FAQs. Example:
  [`clients/32-teeth-hospital.md`](clients/32-teeth-hospital.md)
- **Voice:** Kore (female) ya Puck (male)
- **Answer incoming calls for me:** on rahe

Jo baat text mein nahi hai, us pe AI andaza nahi lagata. Wo bolta hai "staff confirm karega" aur
message le leta hai.

---

## 9. Client ka apna app

1. GitHub pe repo → **Actions** → **Android APK** → **Run workflow**.
2. **App name:** client ka naam (jaise `32 Teeth Reception`), aur **Client:** `32teeth`.
3. 5-10 minute baad run kholein. **Artifacts** mein `phone-app-32teeth` download karein, zip mein
   `app-debug.apk` hai.

App QR se **sirf usi client ke server** se judta hai, isliye har client ka app uske apne deployment
pe chalta hai.

### Phone setup (client ke yahan, phone Wi-Fi pe)

1. **Install:** APK phone mein bhejein aur kholein. "Unknown apps" ki ijazat dein.
2. **Default phone app:** app mein step 1 → **Set**. Vivo, Oppo ya Xiaomi pe "Restricted setting"
   aaye to **Settings → Apps → (app) → ⋮ → Allow restricted settings** karein, phir dobara try
   karein.
3. **Server se jodna:** client ke console mein **Connection** page pe QR khulega. App mein step 2 →
   **Scan QR**. Console mein phone **Online** dikhega.
4. **Call audio:**
   1. Developer options chalu karein (**Settings → About phone → Build number** pe 7 baar tap).
   2. App mein step 3 → **Set up**. **Wireless debugging** on karein → **Pair device with pairing
      code**.
   3. Notification mein **Enter code** dabakar 6-digit code bhejein. "Call audio ready" aana
      chahiye.
5. **Background:** app mein step 4 → **Allow**. Vivo pe **Settings → Battery → Background power
   consumption → (app) → Allow** bhi karein.
6. Phone ko charger pe lagakar ek jagah rakh dein.

---

## 10. Test aur handover

Doosre phone se client ke SIM number pe call karein:

- [ ] AI 1-2 second mein uthata hai, business ka naam leke greeting deta hai
- [ ] Gateway phone pe koi awaaz nahi aati
- [ ] Timings, address, doctor ke baare mein sahi jawab aata hai
- [ ] "Appointment chahiye" bolne pe WhatsApp pe link aata hai
- [ ] Dashboard mein call, transcript aur booking link dikhte hain
- [ ] **Failover test:** Settings mein Gemini key thodi der ke liye galat kar dein aur call karein.
      Backup provider se baat honi chahiye. Saare providers band karke call karein to "कृपया थोड़ी
      देर बाद कॉल करें" sunna chahiye, phir call kategi. Test ke baad sahi key wapas daal dein.

**Client ka purana number:** purane phone mein **Settings → Calls → Call forwarding** se is SIM pe
forward lagayein: hamesha, ya sirf busy aur no-answer pe.

---

## Dashboard

Login karte hi Dashboard khulta hai aur har 15 second mein refresh hota hai. Period: aaj, 7, 30, 90
din ya 12 mahine.

| Hissa | Kya dikhta hai |
|---|---|
| Alerts | Phone offline, call audio band, battery 20% se kam, WhatsApp link fail |
| Numbers | Total, answered, missed, AI ne sambhali, booking links, messages, alag callers, talk time. Saath mein pichhle period se badlav |
| Charts | Har din ki calls, aur din ke kis ghante mein sabse zyada calls aati hain |
| Phones | Online ya offline, audio, battery, network, aaj ki calls |
| AI providers | Kaun chalu hai, kaun limit pe ruka hai, aur kyun |
| Calls | Har call: time, number, result, length, AI, link, message. Click karne pe transcript khulta hai |
| Export CSV | Mahine ki report client ko bhejne ke liye |

---

## Update, backup, logs

| Kaam | Command |
|---|---|
| Ek client update | `sudo bash ~/openline/deploy/update-client.sh 32teeth` |
| Saare clients update | `sudo bash ~/openline/deploy/update-client.sh all` |
| Saare clients ki halat | `bash ~/openline/deploy/list-clients.sh` |
| Logs | `sudo journalctl -u receptionist-32teeth -f` |
| Restart | `sudo systemctl restart receptionist-32teeth` |
| Backup | Roz raat 3 baje `/opt/receptionist/backups/` mein, 14 din tak. Update se pehle bhi ek backup banta hai |

Update se us client ki chal rahi call kat jaati hai, isliye update raat mein karein. Pehle ek client
pe update karke test karein, phir `all` chalayein.

**Client ki settings file:** `/opt/receptionist/clients/32teeth/server/.env`. Isme `GEMINI_API_KEY`,
`WHATSAPP_GATEWAY_URL` aur `WHATSAPP_API_SECRET` bhi daal sakte hain. Console mein daali settings
`.env` se upar maani jaati hain. `.env` badalne ke baad restart karein.

> **Ek server pe kai businesses (purana tareeka):** `.env` mein `MULTI_CLIENT=true` daalne se
> console mein **Clients** page aa jaata hai, aur ek deployment kai businesses sambhal leta hai.
> Usme AI keys sab businesses ke beech share hoti hain, isliye ye tareeka tabhi lein jab calls kam
> hon.

---

## Problem aaye to

| Dikkat | Kya karein |
|---|---|
| Address nahi khulta | DuckDNS mein sahi IP hai? Oracle Security List mein 80/443 khule hain? `sudo systemctl status caddy` |
| Console khulta hai, phone **Offline** | Phone ka internet check karein, app ek baar kholein |
| **Call audio: Stopped** (phone reboot ke baad) | Phone Wi-Fi pe karke app kholein. Uske baad mobile data bhi chalega |
| Caller ko "baad mein call karein" sunaai deta hai | Dashboard → **AI providers** mein dekhein kaun ruka hai aur kyun. Key ya limit theek karein, ya aur providers jodein |
| WhatsApp link nahi gaya | Settings → WhatsApp → **Send test**. Error se pata chalega |
| AI galat jaankari deta hai | Playground → Assistant ka text theek karein. Agli call se lagu hoga |
| Service band hai | `sudo journalctl -u receptionist-32teeth -n 100` |

## Kharcha

- **Server:** Oracle Always Free pe ₹0, ek VM pe kai clients.
- **Address:** DuckDNS pe ₹0. Apna domain lena chahein to saal ka kuch sau rupaye.
- **AI:** client ke apne accounts ke free tier. Zyada calls hon to Gemini ka paid plan (har minute ka
  charge) client ke account pe. Rate [Gemini API pricing](https://ai.google.dev/pricing) pe dekhein.
- **SIM:** client ka normal plan. Incoming calls aam taur pe free hoti hain.

## Kanoon aur bharosa

- AI hamesha khud ko virtual assistant batata hai.
- Call ki recording save nahi hoti, sirf text transcript client ke apne server pe save hota hai.
- AI na bimari batata hai na dawai. Emergency mein turant aane ya emergency number pe call karne ko
  kehta hai.
- Software MIT license ka hai. Apni brand se bech sakte hain, bas `LICENSE` file code ke saath rehni
  chahiye.
