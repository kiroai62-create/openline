"""
Client presentation PDF for the AI phone receptionist.

    pip install reportlab
    python docs/brochure/make_brochure.py --brand "Acme Voice" --phone "+91 90000 00000" \
        --email hello@acme.in --website acme.in --client "City Dental Clinic"

Every option is optional; without --brand the product is called "AI Phone Receptionist".
"""

import argparse
import os
from datetime import date

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Flowable,
    Frame,
    KeepTogether,
    NextPageTemplate,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_DIR = os.path.join(HERE, "..", "..", "android", "app", "src", "main", "res", "font")

INK = colors.HexColor("#14211F")
MUTED = colors.HexColor("#5B6966")
ACCENT = colors.HexColor("#0E6B5C")
ACCENT_DARK = colors.HexColor("#0A4A40")
ACCENT_SOFT = colors.HexColor("#E3F1EE")
LINE = colors.HexColor("#D5DEDC")
PAPER = colors.HexColor("#F6F8F7")
WARM = colors.HexColor("#C07A1E")
WHITE = colors.white

W, H = A4
MARGIN = 18 * mm


def register_fonts():
    try:
        pdfmetrics.registerFont(TTFont("PS", os.path.join(FONT_DIR, "public_sans_regular.ttf")))
        pdfmetrics.registerFont(TTFont("PS-Medium", os.path.join(FONT_DIR, "public_sans_medium.ttf")))
        pdfmetrics.registerFont(TTFont("PS-Bold", os.path.join(FONT_DIR, "public_sans_semibold.ttf")))
        from reportlab.pdfbase.pdfmetrics import registerFontFamily

        registerFontFamily("PS", normal="PS", bold="PS-Bold", italic="PS", boldItalic="PS-Bold")
        return "PS", "PS-Medium", "PS-Bold"
    except Exception:  # fonts missing: fall back to the built-in Helvetica
        return "Helvetica", "Helvetica", "Helvetica-Bold"


REG, MED, BOLD = register_fonts()


def style(name, **kw):
    base = dict(fontName=REG, fontSize=10.5, leading=15.5, textColor=INK)
    base.update(kw)
    return ParagraphStyle(name, **base)


S = {
    "kicker": style("kicker", fontName=BOLD, fontSize=9, leading=12, textColor=ACCENT, spaceAfter=6),
    "h1": style("h1", fontName=BOLD, fontSize=24, leading=30, spaceAfter=8),
    "h2": style("h2", fontName=BOLD, fontSize=14, leading=19, spaceBefore=4, spaceAfter=6),
    "h3": style("h3", fontName=BOLD, fontSize=11.5, leading=15, spaceAfter=3),
    "lede": style("lede", fontSize=12.5, leading=19, textColor=MUTED, spaceAfter=10),
    "body": style("body", spaceAfter=6),
    "small": style("small", fontSize=9, leading=13, textColor=MUTED),
    "card": style("card", fontSize=9.6, leading=14, textColor=MUTED),
    "bullet": style("bullet", leftIndent=12, bulletIndent=0, spaceAfter=3),
    "quote_who": style("qwho", fontName=BOLD, fontSize=8.5, leading=11, textColor=ACCENT),
    "quote": style("quote", fontSize=10, leading=14.5),
    "center": style("center", alignment=TA_CENTER),
}


def p(text, s="body"):
    return Paragraph(text, S[s])


def bullets(items, s="bullet"):
    return [Paragraph(item, S[s], bulletText="\u2022") for item in items]


# ---- drawn pieces -------------------------------------------------------------------------------


class Rule(Flowable):
    def __init__(self, width=None, color=LINE, space=6):
        super().__init__()
        self.w, self.color, self.space = width, color, space

    def wrap(self, aw, ah):
        self.aw = self.w or aw
        return self.aw, self.space * 2

    def draw(self):
        self.canv.setStrokeColor(self.color)
        self.canv.setLineWidth(0.7)
        self.canv.line(0, self.space, self.aw, self.space)


class FlowDiagram(Flowable):
    """Numbered boxes joined by arrows, in one row."""

    def __init__(self, steps, height=50 * mm):
        super().__init__()
        self.steps, self.h = steps, height

    def wrap(self, aw, ah):
        self.aw = aw
        return aw, self.h

    def draw(self):
        c = self.canv
        n = len(self.steps)
        gap = 6 * mm
        bw = (self.aw - gap * (n - 1)) / n
        bh = self.h - 2 * mm
        for i, (title, text) in enumerate(self.steps):
            x = i * (bw + gap)
            c.setFillColor(WHITE if i % 2 == 0 else PAPER)
            c.setStrokeColor(LINE)
            c.roundRect(x, 0, bw, bh, 3 * mm, stroke=1, fill=1)
            c.setFillColor(ACCENT)
            c.circle(x + 7 * mm, bh - 7 * mm, 3.6 * mm, stroke=0, fill=1)
            c.setFillColor(WHITE)
            c.setFont(BOLD, 9.5)
            c.drawCentredString(x + 7 * mm, bh - 8.2 * mm, str(i + 1))
            head = Paragraph(title, style("fdh", fontName=BOLD, fontSize=9, leading=11.5, textColor=INK))
            _, hh = head.wrap(bw - 7 * mm, bh)
            head.drawOn(c, x + 3.5 * mm, bh - 13.5 * mm - hh)
            para = Paragraph(text, style("fd", fontSize=8.2, leading=11, textColor=MUTED))
            _, ph = para.wrap(bw - 7 * mm, bh)
            para.drawOn(c, x + 3.5 * mm, bh - 15 * mm - hh - ph)
            if i < n - 1:
                ax = x + bw + 1 * mm
                ay = bh / 2
                c.setStrokeColor(ACCENT)
                c.setFillColor(ACCENT)
                c.setLineWidth(1.2)
                c.line(ax, ay, ax + gap - 2.6 * mm, ay)
                path = c.beginPath()
                path.moveTo(ax + gap - 2 * mm, ay)
                path.lineTo(ax + gap - 3.6 * mm, ay + 1.4 * mm)
                path.lineTo(ax + gap - 3.6 * mm, ay - 1.4 * mm)
                path.close()
                c.drawPath(path, stroke=0, fill=1)


class Architecture(Flowable):
    """Caller -> SIM phone -> secure server (AI) -> WhatsApp / dashboard."""

    def __init__(self, height=62 * mm):
        super().__init__()
        self.h = height

    def wrap(self, aw, ah):
        self.aw = aw
        return aw, self.h

    def node(self, x, y, w, h, title, sub, fill=WHITE, ink=INK, subink=MUTED):
        c = self.canv
        c.setFillColor(fill)
        c.setStrokeColor(LINE if fill == WHITE else fill)
        c.roundRect(x, y, w, h, 3 * mm, stroke=1, fill=1)
        c.setFillColor(ink)
        c.setFont(BOLD, 10)
        c.drawCentredString(x + w / 2, y + h - 8 * mm, title)
        para = Paragraph(sub, style("an", fontSize=8, leading=10.5, textColor=subink, alignment=TA_CENTER))
        _, ph = para.wrap(w - 6 * mm, h)
        para.drawOn(self.canv, x + 3 * mm, y + h - 11 * mm - ph)

    def arrow(self, x1, y1, x2, y2, label=None, both=True):
        c = self.canv
        c.setStrokeColor(ACCENT)
        c.setFillColor(ACCENT)
        c.setLineWidth(1.1)
        c.line(x1, y1, x2, y2)
        for (ax, ay, dx) in ([(x2, y2, -1)] + ([(x1, y1, 1)] if both else [])):
            path = c.beginPath()
            path.moveTo(ax, ay)
            path.lineTo(ax + dx * 2.4 * mm, ay + 1.3 * mm)
            path.lineTo(ax + dx * 2.4 * mm, ay - 1.3 * mm)
            path.close()
            c.drawPath(path, stroke=0, fill=1)
        if label:
            c.setFillColor(MUTED)
            c.setFont(MED, 7.2)
            c.drawCentredString((x1 + x2) / 2, y1 - 4.2 * mm, label)

    def draw(self):
        aw, h = self.aw, self.h
        gap = 17 * mm
        w = (aw - 3 * gap) / 4
        bh = 30 * mm
        y = h - bh - 2 * mm
        xs = [i * (w + gap) for i in range(4)]
        self.node(xs[0], y, w, bh, "Caller", "Dials your clinic's number from any phone. No app needed.")
        self.node(xs[1], y, w, bh, "Clinic phone", "An Android phone with your SIM. Picks up and stays silent.")
        self.node(xs[2], y, w, bh, "Secure server", "Runs the AI receptionist and keeps the call log.", fill=ACCENT, ink=WHITE, subink=ACCENT_SOFT)
        self.node(xs[3], y, w, bh, "AI voice", "Google Gemini Live: listens and speaks in real time.")
        mid = y + bh / 2
        self.arrow(xs[0] + w, mid, xs[1], mid, "mobile call")
        self.arrow(xs[1] + w, mid, xs[2], mid, "encrypted")
        self.arrow(xs[2] + w, mid, xs[3], mid, "live audio")
        # lower row from the server
        bw2 = w * 1.25
        by = 2 * mm
        bh2 = 18 * mm
        sx = xs[2] + w / 2
        left = sx - bw2 - 6 * mm
        right = sx + 6 * mm
        c = self.canv
        for x, title, sub in [
            (left, "WhatsApp", "Booking link from your number"),
            (right, "Your dashboard", "Calls, transcripts, messages"),
        ]:
            c.setFillColor(ACCENT_SOFT)
            c.setStrokeColor(ACCENT_SOFT)
            c.roundRect(x, by, bw2, bh2, 3 * mm, stroke=0, fill=1)
            c.setFillColor(ACCENT_DARK)
            c.setFont(BOLD, 9.5)
            c.drawCentredString(x + bw2 / 2, by + bh2 - 7 * mm, title)
            c.setFillColor(MUTED)
            c.setFont(REG, 7.6)
            c.drawCentredString(x + bw2 / 2, by + 4.5 * mm, sub)  # short text, fits one line
        c.setStrokeColor(ACCENT)
        c.setLineWidth(1.1)
        c.line(sx, y, sx, by + bh2 + 4 * mm)
        c.line(left + bw2 / 2, by + bh2 + 4 * mm, right + bw2 / 2, by + bh2 + 4 * mm)
        for x in (left + bw2 / 2, right + bw2 / 2):
            c.line(x, by + bh2 + 4 * mm, x, by + bh2 + 1.2 * mm)
            path = c.beginPath()
            path.moveTo(x, by + bh2 + 0.4 * mm)
            path.lineTo(x - 1.3 * mm, by + bh2 + 2.8 * mm)
            path.lineTo(x + 1.3 * mm, by + bh2 + 2.8 * mm)
            path.close()
            c.setFillColor(ACCENT)
            c.drawPath(path, stroke=0, fill=1)


def card_grid(cards, cols=2, title_style="h3"):
    """cards: list of (title, text). Renders a grid of soft cards."""
    cells = []
    for title, text in cards:
        cells.append([p(title, title_style), p(text, "card")])
    rows = [cells[i:i + cols] for i in range(0, len(cells), cols)]
    for r in rows:
        while len(r) < cols:
            r.append("")
    usable = W - 2 * MARGIN
    t = Table(rows, colWidths=[usable / cols] * cols)
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (-1, -1), 11),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 12),
        ("BACKGROUND", (0, 0), (-1, -1), PAPER),
        ("LINEBELOW", (0, 0), (-1, -2), 4, WHITE),
        ("LINEAFTER", (0, 0), (-2, -1), 4, WHITE),
    ]))
    return t


def conversation(lines):
    rows = []
    for who, text in lines:
        is_ai = who != "Caller"
        rows.append([p(who.upper(), "quote_who"), p(text, "quote")])
    t = Table(rows, colWidths=[30 * mm, W - 2 * MARGIN - 30 * mm])
    cmds = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("LINEBELOW", (0, 0), (-1, -2), 0.5, LINE),
        ("BOX", (0, 0), (-1, -1), 0.7, LINE),
    ]
    for i, (who, _) in enumerate(lines):
        if who != "Caller":
            cmds.append(("BACKGROUND", (0, i), (-1, i), ACCENT_SOFT))
    t.setStyle(TableStyle(cmds))
    return t


def key_values(rows, first=46 * mm):
    t = Table([[p(f"<b>{k}</b>"), p(v)] for k, v in rows], colWidths=[first, W - 2 * MARGIN - first])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.5, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
    ]))
    return t


# ---- document -----------------------------------------------------------------------------------


def build(args):
    product = args.brand or "AI Phone Receptionist"
    brand_line = args.brand or ""

    def cover(c, doc):
        c.saveState()
        c.setFillColor(ACCENT_DARK)
        c.rect(0, 0, W, H, stroke=0, fill=1)
        c.setFillColor(ACCENT)
        c.circle(W + 20 * mm, H - 40 * mm, 120 * mm, stroke=0, fill=1)
        c.setFillColor(colors.HexColor("#12826F"))
        c.circle(W - 10 * mm, H - 70 * mm, 60 * mm, stroke=0, fill=1)
        c.setFillColor(WHITE)
        c.setFont(BOLD, 11)
        c.drawString(MARGIN, H - MARGIN - 4 * mm, brand_line.upper() if brand_line else "PRODUCT OVERVIEW")
        c.setFont(BOLD, 34)
        y = H * 0.52
        for line in ["Your clinic's phone,", "answered 24/7", "by an AI receptionist."]:
            c.drawString(MARGIN, y, line)
            y -= 13 * mm
        c.setFillColor(ACCENT_SOFT)
        c.setFont(REG, 13)
        y -= 4 * mm
        for line in [
            "It picks up every call, answers questions about your clinic in",
            "Hindi or English, and sends the patient a booking link on WhatsApp.",
        ]:
            c.drawString(MARGIN, y, line)
            y -= 7 * mm
        # chips
        y -= 10 * mm
        x = MARGIN
        c.setFont(MED, 10)
        for chip in ["Answers in seconds", "Hindi · Hinglish · English", "WhatsApp booking", "Works on your SIM"]:
            tw = pdfmetrics.stringWidth(chip, MED, 10) + 10 * mm
            c.setFillColor(colors.HexColor("#0F5D51"))
            c.roundRect(x, y - 3 * mm, tw, 9 * mm, 4.5 * mm, stroke=0, fill=1)
            c.setFillColor(WHITE)
            c.drawString(x + 5 * mm, y, chip)
            x += tw + 3 * mm
        # footer
        c.setFillColor(ACCENT_SOFT)
        c.setFont(REG, 10)
        if args.client:
            c.drawString(MARGIN, MARGIN + 8 * mm, f"Prepared for {args.client}")
        contact = " · ".join(v for v in [args.phone, args.email, args.website] if v)
        c.drawString(MARGIN, MARGIN, contact or date.today().strftime("%B %Y"))
        c.restoreState()

    def page(c, doc):
        c.saveState()
        c.setStrokeColor(LINE)
        c.setLineWidth(0.6)
        c.line(MARGIN, H - 13 * mm, W - MARGIN, H - 13 * mm)
        c.setFillColor(MUTED)
        c.setFont(MED, 8)
        c.drawString(MARGIN, H - 10.5 * mm, product)
        c.drawRightString(W - MARGIN, H - 10.5 * mm, "Product overview")
        c.line(MARGIN, 13 * mm, W - MARGIN, 13 * mm)
        foot = " · ".join(v for v in [args.phone, args.email, args.website] if v)
        if foot:
            c.drawString(MARGIN, 8.5 * mm, foot)
        c.drawRightString(W - MARGIN, 8.5 * mm, str(doc.page))
        c.restoreState()

    doc = BaseDocTemplate(
        args.out,
        pagesize=A4,
        leftMargin=MARGIN,
        rightMargin=MARGIN,
        topMargin=20 * mm,
        bottomMargin=18 * mm,
        title=f"{product}: product overview",
        author=args.brand or "",
        subject="AI phone receptionist for clinics and local businesses",
    )
    frame = Frame(MARGIN, 18 * mm, W - 2 * MARGIN, H - 38 * mm, id="body", leftPadding=0, rightPadding=0)
    doc.addPageTemplates([
        PageTemplate(id="cover", frames=[frame], onPage=cover),
        PageTemplate(id="page", frames=[frame], onPage=page),
    ])

    story = [NextPageTemplate("page"), PageBreak()]

    # ---- 1. problem / solution
    story += [
        p("THE PROBLEM", "kicker"),
        p("Every missed call is a missed patient.", "h1"),
        p("Patients call when it suits them: early morning, during lunch, late at night, while the "
          "front desk is busy with someone else. When nobody picks up, most of them don't leave a "
          "message. They call the next clinic.", "lede"),
        Spacer(1, 4),
        card_grid([
            ("Busy front desk", "Staff can't answer the phone and look after the patient in front of them at the same time."),
            ("After hours", "Calls after closing time, on Sundays and on holidays go unanswered."),
            ("The same questions", "Timings, address, fees, “do you do implants?” are answered again and again."),
            ("Lost bookings", "A caller who wanted an appointment hangs up without one."),
        ]),
        Spacer(1, 18),
        p("THE SOLUTION", "kicker"),
        p("A receptionist that never misses a call", "h2"),
        p(f"{product} answers your clinic's phone like a trained front-desk assistant. It knows your "
          "services, doctors, timings and address, talks to callers naturally in their own language, "
          "and when they want an appointment, it sends them your booking link on WhatsApp while they are "
          "still on the line. Your staff see every call, transcript and message in one dashboard."),
        Spacer(1, 6),
        *bullets([
            "Picks up every call, day and night, within seconds.",
            "Speaks Hindi, Hinglish and English, and follows the caller when they switch.",
            "Answers only from the information you give it. It never makes up prices or promises.",
            "Sends the booking link on WhatsApp from <b>your</b> clinic number.",
            "Runs on an ordinary Android phone with your SIM. No new phone number and no call centre.",
        ]),
        PageBreak(),
    ]

    # ---- 2. how a call works
    story += [
        p("HOW IT WORKS", "kicker"),
        p("What happens when a patient calls", "h1"),
        p("The patient makes a normal phone call. Nothing to install, no app, no buttons to press.", "lede"),
        FlowDiagram([
            ("Patient calls", "They dial your clinic number from any phone."),
            ("AI picks up", "It greets them with your clinic's name and says it is a virtual assistant."),
            ("Answers questions", "Timings, address, treatments and doctors, from your notes."),
            ("Booking on WhatsApp", "It confirms the number and sends your booking link."),
            ("Staff informed", "Call, transcript and any message appear in your dashboard."),
        ]),
        Spacer(1, 16),
        p("A real conversation", "h2"),
        p("Example from a dental clinic. Conversations happen in the caller's language; this one is translated from Hinglish.", "small"),
        Spacer(1, 6),
        conversation([
            ("Receptionist", "Namaste, welcome to City Dental Clinic. I'm the clinic's virtual assistant. How can I help you?"),
            ("Caller", "I have had pain in a back tooth for two days. Do you do root canals?"),
            ("Receptionist", "Yes, root canal treatment is one of our specialities. It is done under local anaesthesia, so the area is numb. The doctor will check it on a digital X-ray first and explain the cost before starting."),
            ("Caller", "Okay. What are your timings? I want to come tomorrow."),
            ("Receptionist", "We're open every day. Shall I send you our booking link on WhatsApp, to the number ending in 4521?"),
            ("Caller", "Yes, please."),
            ("Receptionist", "Done, I've sent it. You can book from the link or just reply on that chat with a good time. Is there anything else?"),
        ]),
        Spacer(1, 12),
        p("The patient receives this on WhatsApp, from your clinic's number:", "small"),
        Spacer(1, 4),
        Table([[p("Namaste Rahul! City Dental Clinic ko call karne ke liye dhanyavaad.<br/><br/>"
                  "Appointment book karne ke liye ye link kholein: <font color='#0E6B5C'>citydental.in/book</font><br/><br/>"
                  "Ya isi WhatsApp par reply karke apna naam aur aane ka din bata dijiye, hum appointment confirm kar denge.", "quote")]],
              colWidths=[120 * mm],
              style=TableStyle([
                  ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#DCF8C6")),
                  ("LEFTPADDING", (0, 0), (-1, -1), 10), ("RIGHTPADDING", (0, 0), (-1, -1), 10),
                  ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
              ]), hAlign="LEFT"),
        Spacer(1, 6),
        p("The message text can be changed for each clinic.", "small"),
        PageBreak(),
    ]

    # ---- 3. features
    story += [
        p("FEATURES", "kicker"),
        p("Everything a front desk does on the phone", "h1"),
        Spacer(1, 4),
        card_grid([
            ("Always available", "Answers 24 hours a day, 7 days a week, including holidays. Never puts a caller on hold."),
            ("Knows your clinic", "You give it your services, doctors, timings, address, policies and FAQs, as much as a website's worth of text."),
            ("Speaks like a person", "Short, natural replies in Hindi, Hinglish or English. Callers can interrupt it, like a real conversation."),
            ("WhatsApp booking", "Sends your booking link to the caller, or to another number they give. Your WhatsApp assistant takes it from there."),
            ("Takes messages", "If a caller wants a call back or asks something it doesn't know, it notes their name, reason, number and urgency for your staff."),
            ("Call log and transcripts", "Every call is listed with its full text transcript, so you can see what was asked and how it was answered."),
            ("Safe with health questions", "It never diagnoses or suggests medicines. For emergencies it tells the caller to come in at once or call emergency services."),
            ("Never goes down with one provider", "Several AI providers stand by. If one is busy or reaches its limit, the next takes over within seconds, even during a call."),
            ("Silent clinic phone", "The phone that receives the calls stays silent. The conversation happens entirely between the caller and the AI."),
            ("Live analytics", "See calls per day, answered and missed calls, booking links sent and the busiest hours, for any period, with CSV export."),
        ]),
        Spacer(1, 14),
        p("Your dashboard", "h2"),
        *bullets([
            "Call analytics: total, answered and missed calls, calls handled by the AI, booking links sent, talk time, compared with the previous period.",
            "Charts of calls per day and the busiest hours of the day, so you know when patients call.",
            "Alerts when the clinic phone goes offline, its battery is low or a WhatsApp message fails.",
            "See which phones are online, their battery, signal and network.",
            "Read every call's transcript and the messages taken for your staff.",
            "Change what the receptionist knows at any time. It applies from the next call.",
            "Pause the receptionist with one switch, for example when staff want to take calls themselves.",
        ]),
        PageBreak(),
    ]

    # ---- 4. behind the scenes
    story += [
        p("BEHIND THE SCENES", "kicker"),
        p("Simple setup, no new phone line", "h1"),
        p("The receptionist works on your existing SIM. An Android phone at the clinic receives the calls and "
          "connects them securely to our server, where the AI listens and replies in real time.", "lede"),
        Spacer(1, 4),
        Architecture(),
        Spacer(1, 14),
        key_values([
            ("Clinic phone", "An Android phone (Android 11 or newer) with the SIM, kept on charge with Wi-Fi or mobile data. It picks up the call and routes the audio. It is not used for anything else."),
            ("Server", "A deployment dedicated to your clinic: its own data, settings and AI accounts, shared with no other business. It keeps the call log and transcripts and sends the WhatsApp message. All connections are encrypted (HTTPS / secure WebSockets)."),
            ("AI voice", "Google Gemini Live, a real-time voice model that understands and speaks Indian languages. Backed up by other AI providers (Groq, Cloudflare, Sarvam and more): if one reaches its limit or fails, the next takes over automatically, even mid-call."),
            ("WhatsApp", "Connected to your clinic's own WhatsApp number, so patients reply to you directly."),
            ("Your existing number", "Keep it. Forward calls from it to the receptionist's SIM, always or only when the line is busy or unanswered."),
        ]),
        PageBreak(),
    ]

    # ---- 5. getting started
    story += [
        p("GETTING STARTED", "kicker"),
        p("Live in a few steps", "h1"),
        p("We handle the technical setup. From you we need the information your receptionist should know and an hour with the clinic phone.", "lede"),
        FlowDiagram([
            ("Share your details", "Services, doctors, timings, address, fee policy and FAQs."),
            ("Phone setup", "We install the app on the clinic phone and connect it securely."),
            ("WhatsApp", "We connect your WhatsApp number for booking messages."),
            ("Test calls", "We call it together and adjust the answers until you are happy."),
            ("Go live", "Calls are answered from then on. You watch them in the dashboard."),
        ], height=58 * mm),
        Spacer(1, 16),
        p("What we need from you", "h2"),
        Table([
            [p("<b>Information</b>"), p("<b>Equipment</b>")],
            [[*bullets([
                "Clinic name, address and landmark",
                "Opening hours, including Sundays and holidays",
                "Doctors, qualifications and the days they see patients",
                "Treatments offered, and your policy on quoting fees",
                "Emergency contact number",
                "Booking link (website page or WhatsApp)",
                "Common questions patients ask",
            ])],
             [*bullets([
                 "An Android phone, Android 11 or newer",
                 "A SIM with calling (your number, or a new one you forward to)",
                 "A charger and a fixed spot near a power socket",
                 "Wi-Fi at the clinic (needed during setup and after the phone restarts)",
                 "Access to your clinic's WhatsApp number",
             ])]],
        ], colWidths=[(W - 2 * MARGIN) / 2] * 2, style=TableStyle([
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("BACKGROUND", (0, 0), (-1, 0), ACCENT_SOFT),
            ("BACKGROUND", (0, 1), (-1, 1), PAPER),
            ("LINEAFTER", (0, 0), (0, -1), 4, WHITE),
            ("LEFTPADDING", (0, 0), (-1, -1), 10), ("RIGHTPADDING", (0, 0), (-1, -1), 10),
            ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
        ])),
        PageBreak(),
    ]

    # ---- 6. trust, limits, FAQ
    story += [
        p("TRUST AND PRIVACY", "kicker"),
        p("Built to be honest with your patients", "h1"),
        card_grid([
            ("Says it is an AI", "The receptionist introduces itself as the clinic's virtual assistant, and says so whenever asked."),
            ("No call recordings", "Call audio is not recorded. Only a text transcript of each call is stored, on our secure server."),
            ("Only your information", "It answers only from what you give it. Anything else, it says the staff will confirm."),
            ("No medical advice", "No diagnosis, no medicines. Emergencies are directed to come in at once or call emergency services."),
        ]),
        Spacer(1, 14),
        p("Good to know", "h2"),
        *bullets([
            "<b>One call at a time per SIM.</b> For clinics that get several calls at once, we add a second phone and SIM.",
            "<b>The clinic phone must stay on</b>, on charge and connected to the internet. If it is switched off, calls ring as normal and are not answered by the receptionist.",
            "<b>After the phone restarts</b>, it needs to be on Wi-Fi once to resume. Mobile data is fine after that.",
            "<b>Phone models.</b> Call audio relies on Android features that vary by manufacturer. We test your phone before going live and can recommend a model.",
        ]),
        PageBreak(),
        p("FAQ", "kicker"),
        p("Frequently asked questions", "h1"),
        Spacer(1, 4),
        KeepTogether(key_values([
            ("Will callers know it's an AI?", "Yes. It introduces itself as a virtual assistant. Most callers simply get their answer and a booking link."),
            ("Can it book a time slot directly?", "It sends your booking link and invites the patient to reply on WhatsApp, where your team or WhatsApp assistant confirms the time. It never promises a slot itself."),
            ("Can we take calls ourselves sometimes?", "Yes. Pause the receptionist from the dashboard with one switch, or forward calls only when your line is busy or unanswered."),
            ("What if it doesn't know an answer?", "It tells the caller the staff will confirm, takes their name, number and question, and you see the message in the dashboard."),
            ("Can we change what it says?", "Yes, at any time. Update your clinic information in the dashboard and it applies from the next call."),
            ("What if the AI has a problem during a call?", "Another AI provider takes over within seconds and continues the conversation. If none can answer, the caller hears a recorded message asking them to call back later, never a silent line."),
            ("Which languages?", "Hindi, Hinglish and English out of the box. It replies in the language the caller uses."),
        ], first=52 * mm)),
        PageBreak(),
    ]

    # ---- 7. next steps
    contact_rows = [(k, v) for k, v in [("Phone", args.phone), ("Email", args.email), ("Website", args.website)] if v]
    story += [
        Spacer(1, 8 * mm),
        p("NEXT STEP", "kicker"),
        p("Hear it answer your own clinic's phone.", "h1"),
        p("Send us your clinic details and we'll set up a test line, so you can call it and hear how it "
          "answers your patients before you decide.", "lede"),
        Spacer(1, 8),
        *bullets([
            "A short call to understand your clinic and how you handle bookings today.",
            "A test line with your clinic's information, ready to call.",
            "Go live on your own number when you are happy with it.",
        ]),
        Spacer(1, 18),
        p("At a glance", "h2"),
        key_values([
            ("Availability", "24 hours a day, 7 days a week, including holidays"),
            ("Languages", "Hindi, Hinglish and English"),
            ("Booking", "Booking link on WhatsApp, from your clinic's number"),
            ("Your number", "Works on your SIM; forward your existing number if you like"),
            ("What you see", "Live call analytics, every call's transcript and messages, CSV export"),
            ("Privacy", "No call recordings; AI introduces itself; no medical advice"),
            ("You provide", "An Android phone with the SIM, a charger, Wi-Fi and your clinic details"),
        ], first=40 * mm),
        Spacer(1, 18),
    ]
    if contact_rows:
        story += [Rule(color=ACCENT), Spacer(1, 6), p(f"<b>{args.brand or 'Contact us'}</b>", "h2"), key_values(contact_rows, first=30 * mm)]
    doc.build(story)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--brand", default="", help="Your product or company name")
    ap.add_argument("--client", default="", help="Shown on the cover as 'Prepared for ...'")
    ap.add_argument("--phone", default="")
    ap.add_argument("--email", default="")
    ap.add_argument("--website", default="")
    ap.add_argument("--out", default=os.path.join(HERE, "AI-Receptionist-Overview.pdf"))
    build(ap.parse_args())
    print("wrote", ap.parse_args().out)
