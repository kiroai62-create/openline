# SaaS guide: ek server, kai paying customers

Is model mein ek central server pe aap (admin) sab customers chalate hain. Har customer ka apna
login, apna business, apni AI key, apna WhatsApp, apna phone, apna license aur apna plan hota hai.
Customer khud login karke apna data dekh aur badal sakta hai. Aap kisi bhi customer ke account mein
"Log in as customer" se jaakar uske liye sab set kar sakte hain.

> Server ek baar lagana hai (dekhein [DEPLOYMENT.md](DEPLOYMENT.md), Step 1-2). Ye guide uske baad
> ka SaaS wala hissa hai. Billing abhi tak asli Razorpay payment se test nahi hua; test mode mein
> ek baar zaroor try karein.

---

## 1. Pehli baar: admin account

Server chalne ke baad `https://<aapka-server>/` kholein aur pehla account banayein. Pehla account
hamesha **admin** (aap) hota hai. Admin ko ye pages dikhte hain: **Dashboard, Customers, Plans,
Billing**, aur phone/settings.

---

## 2. Razorpay jodna (payment ke liye)

Aapke paas Razorpay **live** account hai. Dashboard se ye lein:

1. **Settings → API Keys** se **Key ID** aur **Key Secret**.
2. **Settings → Webhooks → Add Webhook:**
   - URL: `https://<aapka-server>/v1/billing/webhook`
   - Ek **webhook secret** banayein (koi bhi lamba random string).
   - Ye events chunein: `subscription.activated`, `subscription.charged`, `subscription.halted`,
     `subscription.cancelled`, `subscription.completed`, `payment.captured`.
3. Server ki `.env` mein daalein aur restart karein:
   ```ini
   RAZORPAY_KEY_ID=rzp_live_xxxxx
   RAZORPAY_KEY_SECRET=xxxxx
   RAZORPAY_WEBHOOK_SECRET=xxxxx
   ```

Monthly/yearly plan ke liye Razorpay mein **Plans** banayein (Subscriptions → Plans) aur unki Plan
ID console ke plan mein daalein (neeche). Plan ID na ho to ek-ek period ka one-time payment chalta
hai (auto-renew nahi). Key na ho to customer ko "contact us" dikhega aur aap manually activate kar
sakte hain.

> **Test mode pehle:** Razorpay test keys se ek customer banakar, test card se ek payment karke
> dekh lein ki plan active hota hai, phir live keys daalein. Live payment ke liye Razorpay KYC aur
> website pe Terms, Privacy aur Refund policy zaroori hai.

---

## 3. Plans banana

**Plans** page → **New plan**. Har plan mein:

- Naam, monthly aur yearly price (₹ mein), setup fee, trial days
- **Minutes/mo:** mahine ke AI minute. `-1` = unlimited
- **Max phones:** kitne phone (SIM) jod sakta hai
- Features (ek line, ek feature)
- Razorpay Plan ID (monthly aur yearly) — auto-renew subscription ke liye

Pehli baar 3 plan (Starter, Growth, Pro) apne aap ban jaate hain. Price aur sab kabhi bhi edit kar
sakte hain; purane customers pe asar nahi padta jab tak aap unka plan na badlein.

---

## 4. Deal close → customer banana

**Customers** page → **New customer**:

- **Business name** (AI isi naam se call uthayega)
- **Login email aur password** (customer isse login karega)
- **Plan** aur **billing** (monthly/yearly/lifetime)
- **Lifetime licence** tick karein to bina payment hamesha ke liye chalega
- Customer ki **Gemini API key** (optional; customer baad mein khud bhi daal sakta hai)

Save karte hi:
- Ek **license key** ban jaati hai (jaise `OL-7F3A-91C2-XK4D`)
- Customer ka login ban jaata hai
- Uska business (khaali) ban jaata hai

Customer card kholkar aap: plan/status/license badal sakte hain, password reset kar sakte hain,
lifetime de sakte hain, **Log in as customer** se uske panel mein ja sakte hain, ya account delete
kar sakte hain. Sab kuch **audit log** (Billing page) mein record hota hai.

---

## 5. Customer ka apna panel

Customer `https://<aapka-server>/` pe apne email/password se login karta hai. Use ye dikhta hai:

- **Overview:** apni calls, answered/missed, booking links, minutes, chart
- **My business:** apni jaankari (website ka text), booking link, voice, apni Gemini key. Jo
  badlega wahi AI, WhatsApp aur app mein lagu hoga.
- **WhatsApp:** QR se apna number link, AI auto-replies, inbox (staff reply, AI pause), automation
  settings.
- **Phones:** apne phone ke liye QR nikaalkar apne app se scan karega. QR mein license bhi hoti
  hai, isliye app turant pehchaan leta hai ki ye kis customer ka hai.
- **Plan & billing:** apna plan dekhega aur Razorpay se upgrade/renew karega.
- **Account:** password badlega.

Aap bhi "Log in as customer" se yahi panel kholkar customer ke liye sab set kar sakte hain.

---

## 6. License aur app

- Har customer ko wahi ek APK deti hai (GitHub Actions → Android APK). App khud kisi customer se
  bandha nahi hota; jab customer ke QR se pair hota hai, tab uski license aa jaati hai.
- App pe dikhta hai: **"Licensed to <business> · <plan>"**. License inactive/expire/minute khatam
  ho to app pe **"Licence inactive"** dikhta hai aur app call nahi lagne deta.
- Asli rok **server pe** hai: license active na ho, trial/subscription khatam ho, ya mahine ke
  minute khatam ho jaayein, to server call AI se nahi uthwata. Caller ko "kripya baad mein call
  karein" sunaai deta hai (recorded), call chup-chaap nahi katti.

---

## 7. Roz ke kaam

| Kaam | Kahan |
|---|---|
| Naya customer (deal close) | Customers → New customer |
| Plan ya price badalna | Plans → Edit |
| License band/chalu/dobara | Customer card → License |
| Lifetime dena | Customer card → Lifetime |
| Customer ke liye sab set karna | Customer card → Log in as customer |
| Payments aur audit | Billing page |
| Minute khatam ho raha | Customer ko overview pe 80%/100% pe alert dikhta hai; plan upgrade karein |

## 8. Minute aur limit ka hisaab

- AI minute har call ke end pe us customer ke us mahine ke counter mein jud jaate hain.
- Plan ka minute limit khatam → us mahine AI jawab dena band, customer ko upgrade ka alert. Agle
  mahine counter phir se shuru.
- Har customer apni **apni** Gemini key use karta hai, isliye ek customer ki calls se doosre ki
  limit pe koi asar nahi.

## 9. Dhyan rakhne wali baatein

- Razorpay ka asli payment flow yahan test nahi hua. Test mode mein ek baar poora try karein.
- Baileys WhatsApp official API nahi hai; number block ho sakta hai (dekhein DEPLOYMENT.md).
- Android app ka behaviour phone model pe alag ho sakta hai; abhi sirf Vivo V40 pe test hua hai.
- Live payment ke liye Razorpay KYC aur policies (Terms/Privacy/Refund) zaroori hain.
