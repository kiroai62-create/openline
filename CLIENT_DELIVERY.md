# Client delivery guide: deal close se go-live tak

Ye guide batata hai ki ek naye client ko shuru se aakhir tak kaise deliver karna hai: kya
jaankari leni hai, kya setup karna hai, kya test karna hai, kaise handover karna hai, aur uske baad
har mahine kya dekhna hai.

- **Ek baar ka kaam (sirf aap, pehli baar):** Part A
- **Har naye client ke liye:** Part B se F (lagbhag 1.5 se 2 ghante client ke yahan)
- **Har mahine:** Part G

> Abhi tak asli phone pe naye license wale app ka, asli Razorpay payment ka, aur asli server pe
> deploy scripts ka test nahi hua hai. **Pehle apne khud ke ek "test client" pe poori guide chala
> lein**, phir asli client pe jaayein.

---

## Ye system kaam kaise karta hai (ek nazar mein)

```
Patient ─call─▶ Clinic ka Android phone (aapka app, client ka SIM)
                      │ internet (WebSocket, encrypted)
                      ▼
               Aapka central server (ek hi, saare clients)
               ├─ AI receptionist (client ki apni Gemini key, backup: Groq/Sarvam…)
               ├─ WhatsApp pe booking link (client ka apna WhatsApp gateway)
               ├─ Client ka dashboard (sirf uska data)
               └─ Aapka admin panel (saare clients, plans, license, billing)
```

- Ek hi server, ek hi APK. Har client ka **login, license, plan, AI key, WhatsApp, phone aur
  data alag** hai.
- App ko **license key** se pata chalta hai ki wo kis client ka hai. Key client ke pairing QR
  mein hoti hai, isliye kuch type nahi karna padta.
- License band ho, trial ya payment khatam ho, ya mahine ke minute khatam ho jaayein, to AI call
  nahi uthata.

---

## Part A: ek baar ka setup (aap)

| # | Kaam | Kahan likha hai |
|---|---|---|
| A1 | VM banao aur `setup-vm.sh` chalao (Node, Caddy HTTPS, backups) | [DEPLOYMENT.md](DEPLOYMENT.md) Step 1-3 |
| A2 | Central server ek deployment ki tarah lagao: `sudo bash deploy/new-client.sh platform "AapkiBrand" voice.duckdns.org`. Yahi ek server saare clients chalayega. **`MULTI_CLIENT` mat lagao** | DEPLOYMENT.md Step 4 |
| A3 | `https://<server>/` kholke **admin account** banao (pehla account = admin) | |
| A4 | Settings → Server → **Product name** = aapki brand. Public address daalo | |
| A5 | Settings → **AI providers**: backup keys (Groq, Sarvam, Cloudflare). Ye tab kaam aati hain jab client ki Gemini key limit pe ho | DEPLOYMENT.md Step 6 |
| A6 | Settings → **If the AI can't speak** → "Save and record" (message record hone chahiye) | |
| A7 | Razorpay: keys `/opt/receptionist/clients/platform/server/.env` mein daalo, webhook set karo, phir `sudo systemctl restart receptionist-platform`. **Pehle test mode** | [SAAS.md](SAAS.md) Step 2 |
| A8 | **Plans** page: price, minutes, phones apne hisaab se. Razorpay Plan ID daalo (auto-renew ke liye) | SAAS.md Step 3 |
| A9 | APK banao: GitHub → Actions → **Android APK** → Run workflow → App name = aapki brand | |
| A10 | Client ke liye PDF: `python docs/brochure/make_brochure.py --brand "..." --phone "..." --email ... --website ...` | |
| A11 | Website pe Terms, Privacy aur Refund policy (Razorpay live ke liye zaroori) | |

**A ka test:** apna ek "Test Clinic" customer banakar Part B se F apne khud ke phone pe chala lo.

---

## Part B: deal se pehle (sales)

1. Client ko **PDF** bhejo (A10), aur ek demo call karwao. Demo ke liye aapka test clinic number
   use karo.
2. Plan tay karo: Starter, Growth, Pro, ya custom/lifetime.
3. Client ko batao ki unhe kya dena hoga (B1 aur B2). Ye list WhatsApp pe pehle hi bhej do.

### B1. Client se jaankari (checklist)

- [ ] Business ka naam (jo AI bolega), address, landmark
- [ ] Timings: roz ke, lunch break, Sunday, chhutti
- [ ] Doctor/staff: naam, qualification, kaunse din kitne baje
- [ ] Services aur treatments, fees policy (price batana hai ya "doctor check-up ke baad batayenge")
- [ ] Emergency number
- [ ] Booking link: website ka booking page, ya `https://wa.me/91XXXXXXXXXX`
- [ ] Website ka poora text (copy-paste) aur common sawaal (FAQ)
- [ ] Bhasha: Hindi, English ya dono
- [ ] AI ki awaaz: female (Kore) ya male (Puck)

Example: [`clients/32-teeth-hospital.md`](clients/32-teeth-hospital.md)

### B2. Client ka saaman (checklist)

- [ ] **Android phone, Android 11 ya naya**. Sirf is kaam ke liye, kisi aur kaam mein nahi
- [ ] **SIM** jisme calling ho. Client ka naya number, ya purana number jise is SIM pe forward karenge
- [ ] Charger aur ek fixed jagah, plug ke paas
- [ ] **Wi-Fi** clinic mein (setup ke waqt aur phone restart ke baad zaroori)
- [ ] **Google account** client ka (Gemini key ke liye)
- [ ] Client ka **WhatsApp number** (booking link isi se jaayega)

> Phone ka model pehle se pooch lo. Abhi tak poori tarah sirf **Vivo V40** pe test hua hai.
> Naye model pe go-live se pehle test call zaroor karo (Part E).

---

## Part C: deal close → account banana (10 minute, office se)

1. Admin panel → **Customers → New customer**:
   - Business name
   - Login **email aur password** (client ka). Password strong rakho
   - Plan, aur billing (monthly/yearly). **Lifetime** dena ho to tick karo
   - Client ki Gemini key abhi ho to daal do, warna baad mein
2. Save → **license key** ban jaati hai (`OL-XXXX-XXXX-XXXX`). Customer list mein dikhegi.
3. Customer card kholo → **Log in as** → client ke panel mein **My business** page:
   - Business name, voice, booking link
   - **Everything the receptionist should know**: B1 ki saari jaankari ek saath paste karo
   - Save
4. Wapas admin: banner pe **Back to admin** dabao, phir admin se dobara login karo.

**Payment ka faisla:**
- **Trial** (plan ke trial days): kuch nahi karna. Trial khatam hone pe client khud **Plan &
  billing** se pay karega.
- **Client ne cash/UPI se offline pay kiya:** customer card → Status **Active** → **Paid until**
  date (ya khaali chhodo, ek cycle apne aap lagegi).
- **Lifetime:** customer card → Lifetime on.

---

## Part D: client ke yahan setup (60-90 minute)

### D1. Gemini key (client ka apna account)

1. Client ke Google account se aistudio.google.com/apikey kholo → **Create API key**.
2. Client ke panel (ya Log in as) → **My business → AI key** → paste → Save.

Har client ki apni key hone se ek client ki calls doosre client ki limit nahi khaati.

### D2. WhatsApp gateway (client ka number)

1. 32 Teeth wale WhatsApp gateway (Hugging Face Space + PHP dashboard) ki ek copy client ke liye
   banao. Space ke secrets mein `PHP_URL` aur `API_SECRET` daalo.
2. Client ke WhatsApp se QR scan karo.
3. **My business → WhatsApp**: Space ka `https://...hf.space` address aur `API_SECRET` daalo, phir
   Save.

> WhatsApp gateway unofficial (Baileys) hai, aur number block hone ka thoda risk hai. Client ko
> bata do.

### D3. Phone (sabse zaroori hissa)

Phone **Wi-Fi** pe ho aur charger lagao.

1. **APK install:** APK phone pe bhejo, kholo, aur "Unknown apps" ki ijazat do.
2. **Default phone app:** app mein step 1 → **Set**.
   - Vivo/Oppo/Xiaomi pe "Restricted setting" aaye to **Settings → Apps → (app) → ⋮ → Allow
     restricted settings**, phir dobara try karo.
3. **Server se jodna (license ke saath):**
   - Laptop pe **client ke panel → Phones → Show pairing code**. Ye QR **client ke panel se hi**
     lena hai. Admin ke Connection page wala QR client se nahi judta.
   - App mein step 2 → **Scan QR**.
   - App pe dikhna chahiye: **"Licensed to <business> · <plan>"**. Client ke Phones page pe phone
     **Online** aana chahiye.
4. **Call audio (ek baar):**
   - Developer options chalu karo: Settings → About phone → Build number pe 7 baar tap.
   - App mein step 3 → **Set up** → **Wireless debugging** on → **Pair device with pairing code**.
   - Notification mein **Enter code** dabao, 6-digit code daalo. "Call audio ready" aana chahiye.
5. **Background:** app mein step 4 → **Allow**. Vivo pe **Settings → Battery → Background power
   consumption → (app) → Allow**. Doosre brands pe Autostart aur "No restrictions".
6. Phone ko charger pe lagakar fixed jagah rakh do.

---

## Part E: test calls (go-live se pehle, 15 minute)

Doosre phone se client ke SIM number pe call karo aur ye sab tick karo:

- [ ] AI 1-2 second mein uthata hai, aur business ke naam se greeting deta hai
- [ ] Clinic wale phone pe koi awaaz nahi aati
- [ ] Timing, address aur doctor ke baare mein sahi jawab aata hai
- [ ] Price poochne pe AI andaza nahi lagata ("doctor batayenge")
- [ ] "Appointment chahiye" bolne pe AI number confirm karta hai, aur **WhatsApp pe link aata hai**
- [ ] Hindi mein bolo to Hindi mein jawab, English mein bolo to English mein
- [ ] Client ke panel → **Overview** mein call dikhti hai, minute badhte hain, aur transcript khulta hai
- [ ] Phone ka Wi-Fi band karke mobile data pe ek call karo. Phir bhi chalna chahiye

Kuch galat jawab aaye to **My business** ka text theek karo. Agli call se lagu ho jaata hai.

**Purana number:** client ke purane phone mein **Settings → Calls → Call forwarding** se is SIM
pe forward lagao: hamesha, ya sirf busy aur no-answer pe.

---

## Part F: handover (15 minute)

1. Client ko uska **login** do: `https://<server>/`, email aur password. Pehli baar login karke
   **Account** page se password badalne ko bolo.
2. Client ko 5 minute dikhao:
   - **Overview:** calls, missed calls, booking links, minute kitne bache
   - **My business:** jaankari kaise badlein (timing, doctor ki chhutti)
   - **Phones:** phone online hai ya nahi
   - **Plan & billing:** plan aur renew/upgrade
3. Client ko ye 4 niyam batao:
   - Phone hamesha **charger aur internet** pe rahe, aur us phone pe app band na karein
   - Phone **restart** ho to ek baar Wi-Fi se jodkar app kholna hai
   - Jaankari badle (timing, doctor, offer) to **My business** mein update karna hai
   - AI khud ko virtual assistant batata hai, dawai nahi batata, aur call record nahi karta
4. Apne paas note karo: phone model, SIM number, license key aur go-live ki date.

---

## Part G: har mahine (aap)

| Kaam | Kahan | Kab |
|---|---|---|
| Kaunsa phone offline hai | Admin **Dashboard** → alerts | Roz ek baar |
| Kaunse AI provider limit pe hain | Dashboard → AI providers | Roz |
| Minute 80% se upar wale clients | **Customers** list → Minutes column | Hafte mein |
| Trial khatam / payment due | Customers → Status (Trial, Payment due) | Hafte mein |
| Payments | **Billing** page | Mahine mein |
| Client ko report | Customer card → Log in as → Overview → **Export CSV** / screenshot | Mahine mein |
| Server update | `sudo bash ~/openline/deploy/update-client.sh platform` (raat mein; update se pehle backup apne aap banta hai) | Jab naya code aaye |
| Server ki halat | `bash ~/openline/deploy/list-clients.sh` | Jab chahein |
| Backup | Apne aap roz raat 3 baje (`/opt/receptionist/backups`), 14 din tak | Mahine mein check |

### Upgrade, cancel, lifetime

| Kya karna hai | Kaise |
|---|---|
| Client upgrade kare | Client khud **Plan & billing** → Choose (Razorpay). Ya aap customer card → Plan badlo |
| Offline payment aaya | Customer card → Status Active → Paid until |
| Payment nahi aaya, band karna hai | Customer card → Status **Suspended** (ya License suspended) |
| Wapas chalu | Status Active, License active |
| Lifetime dena | Customer card → Lifetime on |
| Client chhod raha hai | Customer card → **Delete**. Login, phone, license aur data sab hat jaata hai |
| App dobara pair karna hai | Client ke panel → Phones → purana phone Remove → naya code |
| License key leak ho gayi | Admin API se reissue (`POST /v1/customers/:id/reissue-license`), phir phone dobara pair karo |

---

## Problem aaye to (support)

| Client bolta hai | Kya dekhein / karein |
|---|---|
| "AI call nahi utha raha" | 1) Dashboard pe phone **Online** hai? 2) Customer **Active** hai, minute bache hain? 3) App pe "Licence inactive" to nahi? 4) My business mein Gemini key set hai? |
| "Caller ko 'baad mein call karein' sunaai deta hai" | Saare AI fail ho rahe hain. Dashboard → AI providers: kaun limit pe hai aur error kya hai. Client ki Gemini key check karo, backup keys jodo |
| "Phone pe awaaz aa rahi hai" | App → step 3 (call audio). Phone restart hua ho to Wi-Fi pe app kholo |
| "WhatsApp link nahi gaya" | Gateway Space chalu hai? WhatsApp connected hai? My business mein address aur secret sahi hai? |
| "AI galat bata raha hai" | My business ka text theek karo. Jo nahi likha, AI wo nahi bolega |
| "Login nahi ho raha" | Customer card → Reset login password |
| "Phone add nahi ho raha (limit)" | Plan ke Max phones. Upgrade karo |
| "App bol raha Licence inactive" | Customer card: Status, License status, Paid until. Theek karke app ek baar kholo |

Server ke logs: `sudo journalctl -u receptionist-platform -f`. Restart: `sudo systemctl restart receptionist-platform`.

---

## Ek client ka time aur kharcha (andaaza)

| Kaam | Time |
|---|---|
| Account aur jaankari bharna (Part C) | 15-20 min |
| Client ke yahan phone, WhatsApp, Gemini (Part D) | 60-90 min |
| Test aur handover (E, F) | 30 min |

**Kharcha:** server ek hi hai (free tier ya ₹400-800/mahina saare clients ke liye). AI client ki
apni Gemini key pe chalta hai (free limit, uske baad client ke account pe bill). SIM aur phone
client ka. WhatsApp gateway ke liye Hugging Face free Space.

---

## Kanoon aur bharosa (client ko bhi batayein)

- AI hamesha khud ko **virtual assistant** batata hai.
- Call ki **recording nahi** hoti, sirf text transcript save hota hai.
- AI **dawai ya ilaaj nahi batata**. Emergency mein turant aane ya emergency number pe call karne
  ko kehta hai.
- Automated calls aur data ke local kanoon ki zimmedari client aur aapki hai.
