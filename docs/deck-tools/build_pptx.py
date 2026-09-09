# Builds the SOSEL-style deck as an editable .pptx (for Google Slides import).
# Layout mirrors docs/report-qa-deck.html: logo + "[碩論] 主題 N" header, coral→pink
# gradient footer, grey page bubble, black-bordered tables, green identifiers.
import base64, json, re, sys, os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml.ns import qn
from lxml import etree

S = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/Downloads/DepWeaver-Report-QA-deck.pptx")

CORAL, PINK, INK, GREY, GREEN, LINE, BUBBLE = (RGBColor(0xFF,0x7B,0x7B), RGBColor(0xF8,0x6B,0xC5), RGBColor(0x1C,0x1C,0x1C),
    RGBColor(0x6F,0x6F,0x6F), RGBColor(0x2E,0x8B,0x57), RGBColor(0xE3,0xE3,0xE3), RGBColor(0xD9,0xD9,0xD9))
BLUE, PURPLE, WHITE = RGBColor(0x2F,0x5F,0xB3), RGBColor(0xA2,0x4F,0xD1), RGBColor(0xFF,0xFF,0xFF)
FONT, MONO = "Noto Sans TC", "Consolas"
W, H = Inches(13.333), Inches(7.5)

assets = json.load(open(os.path.join(S, "assets.json")))
for k in assets:
    with open(os.path.join(S, k + ".png"), "wb") as f:
        f.write(base64.b64decode(assets[k].split(",", 1)[1]))

prs = Presentation()
prs.slide_width, prs.slide_height = W, H
BLANK = prs.slide_layouts[6]

# ---------- helpers ----------
def gradient(shape):
    f = shape.fill; f.gradient(); f.gradient_angle = 0
    st = f.gradient_stops
    st[0].position, st[0].color.rgb = 0.0, CORAL
    st[1].position, st[1].color.rgb = 1.0, PINK
    shape.line.fill.background()

def solid(shape, rgb, line=None, lw=None):
    shape.fill.solid(); shape.fill.fore_color.rgb = rgb
    if line is None: shape.line.fill.background()
    else:
        shape.line.color.rgb = line
        if lw: shape.line.width = lw

INLINE = re.compile(r"(\*\*.+?\*\*|\{.+?\}|`.+?`)")
def add_runs(p, text, size, color=INK, bold=False, font=FONT):
    for part in INLINE.split(text):
        if not part: continue
        r = p.add_run(); f = r.font; f.name = font; f.size = Pt(size); f.color.rgb = color; f.bold = bold
        if part.startswith("**"): r.text = part[2:-2]; f.bold = True
        elif part.startswith("{"): r.text = part[1:-1]; f.color.rgb = GREEN; f.bold = True
        elif part.startswith("`"): r.text = part[1:-1]; f.name = MONO; f.size = Pt(size - 1)
        else: r.text = part

def set_bullet(p, level, char):
    pPr = p._p.get_or_add_pPr()
    pPr.set("marL", str(int(Inches(0.28 + 0.3 * level)))); pPr.set("indent", str(-int(Inches(0.24))))
    for tag in ("a:buNone", "a:buChar", "a:buAutoNum"):
        for e in pPr.findall(qn(tag)): pPr.remove(e)
    bf = etree.SubElement(pPr, qn("a:buFont")); bf.set("typeface", "Arial")
    bc = etree.SubElement(pPr, qn("a:buChar")); bc.set("char", char)

def textbox(slide, x, y, w, h, blocks, size=15, color=INK):
    """blocks: list of ('sec'|'sub'|'p'|'b'|'bb', text). Returns the shape."""
    tb = slide.shapes.add_textbox(x, y, w, h); tf = tb.text_frame; tf.word_wrap = True
    tf.margin_left = tf.margin_right = Inches(0.05); tf.margin_top = tf.margin_bottom = Inches(0.02)
    first = True
    for kind, text in blocks:
        p = tf.paragraphs[0] if first else tf.add_paragraph(); first = False
        p.space_after = Pt(2)
        if kind == "sec": add_runs(p, "丨 " + text, size + 5, bold=True); p.space_after = Pt(4)
        elif kind == "sub": add_runs(p, text, size + 3, bold=True); p.space_before = Pt(8)
        elif kind == "p": add_runs(p, text, size, color=color)
        elif kind == "b": set_bullet(p, 0, "●"); add_runs(p, text, size, color=color)
        elif kind == "bb": set_bullet(p, 1, "○"); add_runs(p, text, size, color=color)
        elif kind == "small": add_runs(p, text, size - 2, color=GREY)
    return tb

def border(cell, w=12700):
    tcPr = cell._tc.get_or_add_tcPr()
    for tag in ("a:lnL", "a:lnR", "a:lnT", "a:lnB"):
        for e in tcPr.findall(qn(tag)): tcPr.remove(e)
        ln = etree.SubElement(tcPr, qn(tag)); ln.set("w", str(w)); ln.set("cap", "flat"); ln.set("cmpd", "sng"); ln.set("algn", "ctr")
        sf = etree.SubElement(ln, qn("a:solidFill")); c = etree.SubElement(sf, qn("a:srgbClr")); c.set("val", "222222")
        etree.SubElement(ln, qn("a:prstDash")).set("val", "solid")

def table(slide, x, y, w, rows, widths=None, size=13, center_cols=()):
    nr, nc = len(rows), len(rows[0])
    shp = slide.shapes.add_table(nr, nc, x, y, w, Inches(0.36) * nr); t = shp.table
    tblPr = t._tbl.tblPr; tblPr.set("bandRow", "0"); tblPr.set("firstRow", "0")
    sid = tblPr.find(qn("a:tableStyleId"))
    if sid is not None: tblPr.remove(sid)
    if widths:
        tot = sum(widths)
        for i, fr in enumerate(widths): t.columns[i].width = int(w * fr / tot)
    for r, row in enumerate(rows):
        for c, val in enumerate(row):
            cell = t.cell(r, c); cell.fill.solid(); cell.fill.fore_color.rgb = WHITE
            cell.margin_left = cell.margin_right = Inches(0.08); cell.margin_top = cell.margin_bottom = Inches(0.03)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE if r == 0 else MSO_ANCHOR.TOP
            tf = cell.text_frame; tf.word_wrap = True; p = tf.paragraphs[0]
            add_runs(p, val, size, bold=(r == 0))
            if r == 0 or c in center_cols: p.alignment = PP_ALIGN.CENTER
            border(cell)
    return shp

def codebox(slide, x, y, w, h, text, size=9.5):
    box = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h); solid(box, WHITE, RGBColor(0x22,0x22,0x22), Pt(1))
    tf = box.text_frame; tf.word_wrap = True; tf.margin_left = tf.margin_right = Inches(0.12); tf.margin_top = tf.margin_bottom = Inches(0.08)
    tf.vertical_anchor = MSO_ANCHOR.TOP
    for i, line in enumerate(text.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph(); p.space_after = Pt(0)
        r = p.add_run(); r.text = line; r.font.name = MONO; r.font.size = Pt(size); r.font.color.rgb = INK
    return box

def tag(slide, x, y, text, kind):
    w = Inches(0.22 * len(text) + 0.35)
    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, Inches(0.3)); s.adjustments[0] = 0.3
    if kind == "code": solid(s, BLUE)
    elif kind == "ai": solid(s, PURPLE)
    else: gradient(s)
    tf = s.text_frame; tf.margin_top = tf.margin_bottom = Inches(0); p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = text; r.font.size = Pt(11); r.font.bold = True; r.font.color.rgb = WHITE; r.font.name = FONT
    return s

def frame(slide, title, idx, page):
    slide.shapes.add_picture(os.path.join(S, "logo.png"), Inches(0.25), Inches(0.14), height=Inches(0.48))
    tb = slide.shapes.add_textbox(Inches(1.55), Inches(0.1), Inches(9), Inches(0.55)); tf = tb.text_frame; p = tf.paragraphs[0]
    add_runs(p, title, 22, bold=True)
    if idx: r = p.add_run(); r.text = "   " + str(idx); r.font.size = Pt(11); r.font.bold = True; r.font.name = FONT
    for i in range(3):
        b = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, W - Inches(0.62), Inches(0.24 + 0.09 * i), Inches(0.32), Inches(0.03)); solid(b, CORAL)
    ln = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.2), Inches(0.73), W - Inches(0.4), Emu(9525)); solid(ln, LINE)
    ft = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, H - Inches(0.36), W, Inches(0.36)); gradient(ft)
    ov = slide.shapes.add_shape(MSO_SHAPE.OVAL, W - Inches(0.78), H - Inches(0.86), Inches(0.56), Inches(0.56)); solid(ov, BUBBLE)
    tf = ov.text_frame; tf.margin_top = tf.margin_bottom = Inches(0); p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = str(page); r.font.size = Pt(16); r.font.bold = True; r.font.color.rgb = WHITE; r.font.name = FONT

def box(slide, x, y, w, h, title, lines, hot=False, label=None, size=13):
    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h); s.adjustments[0] = 0.12
    if hot: gradient(s)
    else: solid(s, WHITE, CORAL, Pt(2))
    tf = s.text_frame; tf.word_wrap = True; tf.vertical_anchor = MSO_ANCHOR.TOP
    tf.margin_left = tf.margin_right = Inches(0.25); tf.margin_top = Inches(0.35)
    col = WHITE if hot else INK
    p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER; add_runs(p, title, size + 4, color=col, bold=True); p.space_after = Pt(8)
    for ln in lines:
        p = tf.add_paragraph(); add_runs(p, ln, size, color=col); p.space_after = Pt(2)
    if label:
        lb = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x + Inches(0.15), y - Inches(0.22), Inches(0.24 * len(label) + 0.4), Inches(0.42)); lb.adjustments[0] = 0.25
        if hot: solid(lb, WHITE)
        else: gradient(lb)
        tf = lb.text_frame; tf.margin_top = tf.margin_bottom = Inches(0); p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
        r = p.add_run(); r.text = label; r.font.size = Pt(12); r.font.bold = True; r.font.name = FONT; r.font.color.rgb = PINK if hot else WHITE

def arrow(slide, x, y, text):
    a = slide.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, x, y, Inches(1.0), Inches(0.6)); gradient(a)
    tf = a.text_frame; p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = text; r.font.size = Pt(12); r.font.bold = True; r.font.color.rgb = WHITE; r.font.name = FONT

def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text

L, TOP, FULL = Inches(0.45), Inches(0.95), W - Inches(0.9)
COL = (FULL - Inches(0.4)) / 2
RIGHT = L + COL + Inches(0.4)

# ---------- 1 cover ----------
s = prs.slides.add_slide(BLANK)
s.shapes.add_picture(os.path.join(S, "wave.png"), 0, 0, W, H)
s.shapes.add_picture(os.path.join(S, "logo.png"), Inches(0.8), Inches(2.05), height=Inches(0.62))
tb = s.shapes.add_textbox(Inches(0.75), Inches(2.75), Inches(8), Inches(1.1)); p = tb.text_frame.paragraphs[0]; add_runs(p, "碩一週進度會議", 48, bold=True)
tb = s.shapes.add_textbox(Inches(0.8), Inches(3.85), Inches(9), Inches(0.5)); p = tb.text_frame.paragraphs[0]
add_runs(p, "會議時間：2026/9　15:30　·　主題：報告產出後的對話問答（DepWeaver Report Q&A）", 14, color=GREY)
pill = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.7), Inches(6.05), Inches(1.75), Inches(0.46)); pill.adjustments[0] = 0.2; gradient(pill)
p = pill.text_frame.paragraphs[0]; p.alignment = PP_ALIGN.CENTER; r = p.add_run(); r.text = "學生：李安喻"; r.font.size = Pt(16); r.font.bold = True; r.font.color.rgb = WHITE; r.font.name = FONT
s.shapes.add_picture(os.path.join(S, "cat.png"), W - Inches(4.9), Inches(2.9), height=Inches(3.6))
for i in range(3):
    b = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, W - Inches(0.85), Inches(0.5 + 0.09 * i), Inches(0.34), Inches(0.03)); solid(b, CORAL)
notes(s, "老師上次說報告產出後要能對話問細節，可以用 RAG。這禮拜做完了，先講它怎麼運作、哪些是程式碼哪些是 LLM，再講限制與優缺點，最後講改了幾版、數字進步多少。")

# ---------- 2 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 1, 2)
textbox(s, L, TOP, FULL, Inches(1.6), [
    ("sub", "老師反饋：報告產生後，讓使用者可以對話詢問細節（可用 RAG）"),
    ("p", "讀者看完報告會問：「{ledgerwriter} 什麼時候叫 {balancereader}？」「哪些邊沒被流量跑到？」「改 {userservice} 會影響誰？」——答案都在證據裡，但散在報告、圖、覆蓋率三處。"),
    ("sub", "現況的三個阻礙")], size=15)
table(s, L, Inches(2.75), FULL, [
    ["阻礙", "說明"],
    ["checkpoint 產完報告就刪", "報告貼出後 checkpoint 隨即清掉，事後沒有東西可查。要問，就得另外留一份「報告的殘留物」。"],
    ["對話入口是 intent 分類", "@bot → 分類成 capability → 按 Perform。一個「動作」按一次合理，一個「問題」按一次不合理。"],
    ["沒有檢索基礎", "repo 內只有關鍵字比對，沒有任何 embedding／向量。RAG 要從零建。"]], widths=[26, 74], size=13)
notes(s, "三個阻礙都是現況造成的，不是 RAG 本身難。第一個最關鍵：報告產完 checkpoint 就刪，所以第一件事是在刪之前另存一份給問答用的知識庫。")

# ---------- 3 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 2, 3)
textbox(s, L, TOP, COL, Inches(5.5), [
    ("sec", "使用方式"),
    ("b", "**Generate report**：報告、圖、覆蓋率照常貼出。刪 checkpoint 前多存一份 archive：報告全文、graph JSON、覆蓋率、證據 notes。"),
    ("b", "**自動開 thread**：bot 貼「💬 Ask DepWeaver about this report」並在下面開 public thread，開頭附幾個用真實節點名組的範例問題。"),
    ("b", "**問與答**：thread 內任何訊息都是問題，不用 @bot、不用按鈕，中英文都行，可以連續追問。"),
    ("sub", "為什麼用 thread 不加 capability"),
    ("b", "問題天然黏在它的報告底下，一人多份報告各自成 thread"),
    ("b", "多輪追問有自然邊界（記最近 6 輪）"),
    ("b", "不用按鈕")], size=14)
textbox(s, RIGHT, TOP, COL, Inches(0.5), [("sec", "實際對話")])
bx = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, RIGHT, Inches(1.5), COL, Inches(2.6)); bx.adjustments[0] = 0.08; solid(bx, WHITE, CORAL, Pt(2))
tf = bx.text_frame; tf.word_wrap = True; tf.margin_left = tf.margin_right = Inches(0.2); tf.margin_top = Inches(0.15); tf.vertical_anchor = MSO_ANCHOR.TOP
p = tf.paragraphs[0]; add_runs(p, "**使用者：**改 {userservice} 會影響誰？", 13); p.space_after = Pt(8)
p = tf.add_paragraph(); add_runs(p, "**DepWeaver：**直接呼叫 {userservice} 的只有 {frontend}（runtime 觀測到，6 requests）；再往上是 {istio-ingressgateway}。{userservice} 自己依賴 {accounts-db}（8187 TCP 連線，是連線數不是請求數）。所以 userservice 異常時受影響的是登入與首頁載入這條路徑。", 13)
notes(s, "示範時直接在 thread 打字就好。範例問題是半寫死：句型固定，服務名取圖上邊數最多的那個。")

# ---------- 4 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 3, 4)
textbox(s, L, TOP, FULL, Inches(0.5), [("sec", "一個問題走六步，只有兩步是 LLM")])
t = table(s, L, Inches(1.45), FULL, [
    ["步", "做什麼", "細節", "誰做"],
    ["1", "判定是不是問答 thread", "thread id 對 archive 索引；不是就走原本流程", "程式碼"],
    ["2", "偵測節點、產事實表", "問句裡的服務名 → 進出邊、證據等級、連線數、傳遞閉包、層級", "程式碼"],
    ["3", "NL → 圖查詢", "語意路由：問句向量對意圖例句算相似度，夠像就選算子（零額外呼叫）；不確定才呼叫 LLM，只准輸出 JSON 計畫，參數經程式驗證", "路由先、LLM 後"],
    ["4", "執行圖查詢", "15 個算子：impact-of、uncovered、deploy-order…全是查表與 BFS", "程式碼"],
    ["5", "檢索報告段落", "BM25 ∪ embedding cosine → RRF 融合 → 取前 8 段", "程式碼＋embedding"],
    ["6", "講人話", "只拿到上面的 context 與最近 6 輪對話；沒有的就說沒有", "LLM"]], widths=[5, 22, 57, 16], size=12, center_cols=(0, 3))
for r, c in ((1, BLUE), (2, BLUE), (4, BLUE)):
    for run in t.table.cell(r, 3).text_frame.paragraphs[0].runs: run.font.color.rgb = c; run.font.bold = True
for r, c in ((3, PINK), (5, PINK), (6, PURPLE)):
    for run in t.table.cell(r, 3).text_frame.paragraphs[0].runs: run.font.color.rgb = c; run.font.bold = True
textbox(s, L, Inches(4.75), FULL, Inches(1.9), [
    ("sub", "給 LLM 的 context 有權威順序"),
    ("b", "**1 圖上的事實**（步驟 2、4，程式碼算的）— 最高，段落與它衝突時以它為準"),
    ("b", "**2 覆蓋率**（程式碼算的）"),
    ("b", "**3 檢索到的段落**（報告文字是 LLM 寫的，會飄，只拿來補措辭、角色、限制）")], size=14)
notes(s, "一句話：事實由程式碼算，LLM 只負責理解問題和講人話。步驟 3 是唯一讓 LLM「想」的地方，但它最壞只能選錯查詢，不可能說錯事實。")

# ---------- 5 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 4, 5)
textbox(s, L, TOP, FULL, Inches(0.5), [("sec", "老師說的 RAG，做成兩路")])
tag(s, L, Inches(1.55), "文字 RAG", "code"); textbox(s, L + Inches(1.6), Inches(1.5), Inches(2), Inches(0.4), [("sub", "步驟 5")])
textbox(s, L, Inches(2.0), COL, Inches(4.5), [
    ("b", "**切段**：報告與證據 notes 依 Markdown 標題切，每段帶標題路徑，超過 1800 字再拆"),
    ("b", "**BM25**：永遠有。連字號 id 整個與拆開都索引（{accounts-db} → accounts、db）；中文切成單字＋bigram（相鄰兩字：資料庫 → 資料、料庫），不用斷詞器也能對上中文段落"),
    ("b", "**Embedding**：text-embedding-3-small，archive 建立時整批算一次；提問只 embed 問句。失敗退回純 BM25"),
    ("b", "**融合**：Reciprocal Rank Fusion，不必校準兩種分數")], size=13)
tag(s, RIGHT, Inches(1.55), "GraphRAG", "ai"); textbox(s, RIGHT + Inches(1.6), Inches(1.5), Inches(2), Inches(0.4), [("sub", "步驟 2–4")])
textbox(s, RIGHT, Inches(2.0), COL, Inches(4.5), [
    ("b", "**事實表**（不經 LLM）：每條邊一行 `a -> b [db]; confidence=documented; runtime observed: YES (8187 TCP connections)`"),
    ("b", "**語意路由**：14 個意圖，各十來句中英例句；問句先把服務名換成 X／Y 再比對"),
    ("b", "**查詢 DSL**：LLM 兜底時只准產 `[{\"op\":\"impact-of\",\"args\":[\"userservice\"]}]`，節點參數必須解析到圖上唯一 id"),
    ("b", "**同源**：`uncovered` 直接呼叫 CoverageAnalyzer，`deploy-order` 用畫圖的同一套分層，**thread 答案不會和頻道貼的覆蓋率、圖矛盾**")], size=13)
notes(s, "為什麼不只做向量 RAG：依賴問題多半是圖上的查詢，用向量找段落是繞遠路，而且段落是 LLM 寫的報告。兩路都是「先找證據、再讓模型只依證據回答」。")

# ---------- 6 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 5, 6)
textbox(s, L, TOP, COL + Inches(0.3), Inches(5.9), [
    ("sec", "事實表怎麼產（程式碼，零 LLM）"),
    ("sub", "① 偵測問句點名的節點"),
    ("b", "id 展開成幾種拼法：{accounts-db}、accounts db、accountsdb；去 `ts-`／`-service` 的核心名（{ts-order-service} → order）"),
    ("b", "邊界只看 ASCII 字母數字：「請問frontend依賴誰」抓得到，frontends 不算；角色詞「前端」「閘道／入口」也認"),
    ("sub", "② 每個點名的節點一份事實表"),
    ("b", "kind、deployed、image／replicas、tier"),
    ("b", "進出邊每條一行：型別、信心、來源、有無觀測、次數、證據；兩個方向的傳遞閉包：它壞了誰受影響、它要跑起來誰得先在"),
    ("b", "點名兩個節點 → 直接邊，否則最短路徑，否則明講不可達"),
    ("sub", "③ 全圖摘要永遠附上"),
    ("b", "節點數依 kind、邊數依三層信心、未部署清單、零邊節點、tier 表與反推的啟動順序"),
    ("sub", "怎麼算：全是迴圈與查表，零 LLM"),
    ("b", "進出邊＝掃所有邊比對 source／target；閉包與最短路徑＝BFS"),
    ("b", "摘要＝掃節點依 kind 計數、掃邊依三級信心計數；tier 用產圖時存進節點的 layer，**和圖上分層同一份**"),
    ("b", "同一張圖問一百次，結果一模一樣")], size=11.5)
textbox(s, RIGHT + Inches(0.3), TOP, COL - Inches(0.3), Inches(0.4), [("sub", "實際餵給模型的一段")])
codebox(s, RIGHT + Inches(0.3), Inches(1.4), COL - Inches(0.3), Inches(3.9), """## Node: userservice
- Kind: service
- Deployed: yes (a matching Deployment is running)
- Tier: 2 (0 = entry; deeper tiers are called later)
- Depends on (outgoing edges): 1
  - userservice -> accounts-db [db]; confidence=documented;
    provenance=code; runtime observed: YES (8187 TCP
    connections — a connection count, not requests);
    evidence: istio_tcp_connections_opened_total
- Depended on by (incoming edges): 1
  - frontend -> userservice [sync-http]; confidence=observed;
    provenance=runtime+code; runtime observed: YES (6 requests)
- Everything it transitively depends on: 1 — accounts-db (depth 1)
- Everything that transitively depends on it: 2 —
  frontend (depth 1), istio-ingressgateway (depth 2)""", size=9)
textbox(s, RIGHT + Inches(0.3), Inches(5.4), COL - Inches(0.3), Inches(1.2), [("p", "這一段全是查表：來源、目標、布林值、計數，跟報告第 5 節同一套原則——能算的不問模型。")], size=13)
notes(s, "事實表是「不會錯的那一半」。模型拿到的不是圖片也不是報告全文，是這種已經算好的文字，它只要照著講。")

# ---------- 7 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 6, 7)
textbox(s, L, TOP, COL, Inches(6), [
    ("sec", "語意路由怎麼做"),
    ("sub", "意圖卡：14 張，每張十來句中英例句"),
    ("p", "dependencies-of、dependents-of、impact-of、startup-needs、path、uncovered、observed-edges、db-users、deploy-order、undeployed、externals、async、mentioned-only、about-report（問報告本身，不查圖也不呼叫 LLM）"),
    ("sub", "流程"),
    ("b", "啟動後把所有例句 embed 一次，留在記憶體"),
    ("b", "**遮名**：問句裡點名的服務換成 X／Y／Z，「frontend 依賴誰」→「X 依賴誰」，和例句同形"),
    ("b", "問句向量對每句例句算 cosine，每張卡取最高分"),
    ("b", "**有把握**的條件：最高分 ≥ 0.58 且領先第二名 ≥ 0.04，或最高分 ≥ 0.85"),
    ("b", "需要節點的意圖若問句沒點名節點 → 視為沒把握"),
    ("b", "沒把握 → 交給 LLM planner（它看得到 id 清單）"),
    ("sub", "只剩兩條規則，都是向量會忽略的功能詞"),
    ("b", "否定：「沒／未／never／not」讓 observed-edges 變 uncovered"),
    ("b", "方向：「誰依賴／who calls」是 dependents-of、「依賴誰／depend on」是 dependencies-of")], size=12)
textbox(s, RIGHT, TOP, COL, Inches(0.4), [("sub", "走一次：「前端依賴誰？」")])
table(s, RIGHT, Inches(1.4), COL, [
    ["步驟", "結果"], ["偵測節點", "角色詞「前端」→ {frontend}"], ["遮名", "「X 依賴誰？」"],
    ["embed 後比對", "dependencies-of {1.00}（例句原句）、dependents-of 0.86"], ["方向規則", "「依賴誰」→ 維持 dependencies-of"],
    ["信心", "1.00 ≥ 0.85 → 有把握，不呼叫 LLM"], ["產出查詢", "`dependencies-of(frontend)`"]], widths=[30, 70], size=12)
textbox(s, RIGHT, Inches(4.0), COL, Inches(1.6), [
    ("sub", "為什麼不是 regex"),
    ("b", "regex 是窮舉：第一輪 12 題就有 2 句沒列到"),
    ("b", "例句會泛化：換句話、換語言都對得上；加意圖是加句子"),
    ("b", "用的是檢索本來就算好的向量，**零額外呼叫**（遮名時多一次 embedding，不是 chat）"),
    ("b", "遮名的理由：第一次校準帶服務名的問句分數普遍低 0.1–0.2；遮掉之後 33 句全對")], size=11.5)
table(s, RIGHT, Inches(5.55), COL, [
    ["門檻怎麼定：三個分數帶", "分數", "對應的條件"],
    ["問句幾乎就是某句例句", "0.97–1.00", "≥ 0.85 免看第二名"],
    ["換句話說、意思對", "0.54–0.73", "≥ 0.58 且領先 ≥ 0.04（打平就交 LLM）"],
    ["圖上沒有對應意圖", "0.38–0.50", "低於 0.58 → 沒把握（保守：多問 LLM 比選錯好）"]], widths=[34, 18, 48], size=10.5, center_cols=(1,))
notes(s, "老師可能問「這不就是分類器？」——是，是一個不用訓練的分類器：類別的定義就是例句，改例句就改行為，錯了可以看是哪句例句害的。")

# ---------- 8 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 7, 8)
textbox(s, L, TOP, COL + Inches(0.4), Inches(0.5), [("sec", "圖查詢怎麼執行")])
table(s, L, Inches(1.45), COL + Inches(0.4), [
    ["算子", "怎麼算"],
    ["dependencies-of(X)／dependents-of(X)", "直接篩 source＝X／target＝X 的邊"],
    ["impact-of(X)／startup-needs(X)", "沿 incoming／outgoing 做 BFS，依 depth 分組"],
    ["path(X, Y)", "兩個方向各做一次 BFS 最短路徑"],
    ["uncovered", "**直接呼叫 CoverageAnalyzer**，和頻道貼的覆蓋率同一個數字；greenfield 明講「未量測」"],
    ["observed／unobserved／mentioned-only", "依 runtimeObserved、confidence 篩邊"],
    ["db-users／externals／async", "依目標節點 kind（db／external／queue）篩邊"],
    ["undeployed", "deployed＝false 的節點；greenfield 明講「沒查叢集」"],
    ["deploy-order", "用畫圖的同一套 tier，最深層先；外部主機排除、註明假設可用"]], widths=[38, 62], size=11)
textbox(s, L, Inches(5.05), COL + Inches(0.4), Inches(1.7), [
    ("sub", "LLM 兜底時的驗證"),
    ("b", "算子必須在目錄內、參數個數要對；節點參數必須解析到圖上唯一 id，對不上整條丟掉；最多 3 條"),
    ("b", "模型最壞只能「選錯查詢」，不可能「說錯事實」")], size=12)
textbox(s, RIGHT + Inches(0.4), TOP, COL - Inches(0.4), Inches(0.4), [("sub", "實際輸出：impact-of(ts-station-service)")])
codebox(s, RIGHT + Inches(0.4), Inches(1.4), COL - Inches(0.4), Inches(3.1), """### Query: impact-of(ts-station-service)
18 node(s), by distance from ts-station-service
(what it impacts, nearest first):
- depth 1: ts-admin-route-service, ts-basic-service,
  ts-order-other-service, ts-admin-travel-service,
  ts-order-service
- depth 2: ts-gateway-service, ts-preserve-service,
  ts-travel-service, ts-preserve-other-service,
  ts-travel2-service, ts-cancel-service,
  ts-admin-order-service, ts-seat-service,
  ts-voucher-service
- depth 3: ts-wait-order-service, ts-travel-plan-service,
  ts-route-plan-service, ts-rebook-service""", size=9)
textbox(s, RIGHT + Inches(0.4), Inches(4.6), COL - Inches(0.4), Inches(2.2), [
    ("p", "結果放進 context 的「GRAPH FACTS」最前面，權威高於報告段落。模型把它講成人話，不能加不在裡面的節點。"),
    ("sub", "三個產物同源"),
    ("p", "圖、覆蓋率、thread 的回答都從同一個 `DependencyGraph` 物件算出來，所以不可能互相矛盾——上次「報告說 unknown、圖畫實線」的病不會在 thread 重演。")], size=12)
notes(s, "這一頁是 8/14 那個 A2 設計的本體：NL → 結構化查詢 → 確定性執行 → 模型講結果。")

# ---------- 9 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 8, 9)
textbox(s, L, TOP, FULL, Inches(0.5), [("sec", "時間上的限制")])
table(s, L, Inches(1.45), FULL, [
    ["項目", "值", "說明"],
    ["archive 保留", "7 天", "過期後 thread 提問會回「已過期，請重跑分析」；存檔案，bot 重啟不丟"],
    ["對話記憶", "餵 6 輪 · 存 40 則", "模型每次看最近 6 輪問答"],
    ["一題耗時", "約 4–12 秒", "injection 檢查小呼叫＋embedding＋回答大呼叫；路由沒把握時多 1 次 planner 小呼叫。HTTP 呼叫有 timeout（連線 15 秒、讀取 180 秒）"],
    ["context 上限", "段落 24k 字 · 前 8 段", "事實表另計；gpt-4.1-mini 的 context 夠大"],
    ["Discord thread 封存", "依伺服器設定", "封存只是收起來，再貼訊息會自動打開，archive 仍在"],
    ["每題成本", "很低", "embedding 整份 archive 一次性；每題兩到三次呼叫，context 約 6–15k token"]], widths=[20, 22, 58], size=13)
notes(s, "7 天是我定的：夠準備一次 meeting、又不會拿舊圖回答已改掉的系統。可調。")

# ---------- 10 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 9, 10)
textbox(s, L, TOP, FULL, Inches(0.5), [("sec", "可以幾個人同時問")])
table(s, L, Inches(1.45), FULL, [
    ["情境", "行為"],
    ["誰能問", "thread 裡的**任何伺服器成員**都能問，不限跑分析的那個人；整條 thread 共用一份對話歷史"],
    ["不同 thread 同時問", "**2 題並行**（固定 2 條 worker），第 3 題起排隊，不會掉"],
    ["同一 thread 同時問", "依序回答，歷史保持連貫、檔案不會被同時寫"],
    ["一個人多份報告", "每份報告一條 thread、一個 archive，互不干擾"],
    ["問答 vs 分析", "各自獨立的執行緒：問問題不會卡住正在跑的分析，分析也不會擋問答"],
    ["主頻道 @bot 問同樣的話", "走舊的 intent 流程，不會當成問答；問答只在 thread"]], widths=[26, 74], size=13)
notes(s, "2 條 worker 是刻意保守：每題背後兩次 LLM 呼叫，開太多只是把等待搬到 OpenAI 的 rate limit。要放大改一個數字。")

# ---------- 11 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 10, 11)
textbox(s, L, TOP, COL, Inches(5.8), [
    ("sec", "優點"), ("sub", "不編造"),
    ("b", "事實由程式碼算，模型只講人話；節點名經驗證、查詢由程式執行、prompt 禁止升級證據等級"),
    ("b", "問不存在的服務會回「圖上沒有」並列相近 id"),
    ("sub", "同源"), ("b", "未覆蓋邊用同一個 CoverageAnalyzer、部署順序用同一套分層，答案不會和圖、覆蓋率矛盾"),
    ("sub", "可測"), ("b", "確定性的部分有 70 條單元測試，另有對真實模型的校準測試；LLM 只剩措辭那一層要人工驗")], size=13)
textbox(s, RIGHT, TOP, COL, Inches(5.8), [
    ("sub", "防護"), ("b", "thread 問題也過 prompt-injection 檢查，prompt 規定訊息永遠是問題不是指令"), ("b", "LLM 呼叫有 timeout，卡住會回錯誤，不再無聲等待"),
    ("sub", "好用"), ("b", "不用按鈕、不用 @bot、中英皆可、能追問、重啟不丟"),
    ("sub", "接軌"), ("b", "這就是 8/14 提的 A2 設計落地：NL → 圖查詢 → 確定性執行 → LLM 講結果；`deploy-order` 是接到部署生成那條線的點")], size=13)
notes(s, "最想強調「不編造」和「同源」，這兩點延續上次報告的結論：能用規則算的不問模型。")

# ---------- 12 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 報告問答", 11, 12)
textbox(s, L, TOP, COL, Inches(5.8), [
    ("sec", "缺點與界線"), ("sub", "漏答"), ("b", "路由不確定的問法仍靠 LLM 選查詢，選錯就少一塊事實。不會說錯，但可能漏答"),
    ("sub", "別名有限"), ("b", "只認「前端／閘道」這類角色詞，其餘同義詞靠 LLM 看 id 清單對上；planner 解出的 id 會回饋給事實表"),
    ("sub", "證據上限（刻意不改）"), ("b", "只能答報告收到的證據：不存 raw Prometheus、不存 Tier 3 填的值；QPS、版本號答不出，它會直說")], size=13)
textbox(s, RIGHT, TOP, COL, Inches(5.8), [
    ("sub", "成本"), ("b", "每題兩到三次 LLM 呼叫加一次 embedding；embedding 依賴 OpenAI，掛了退回純 BM25"),
    ("sub", "只驗過 greenfield"), ("b", "BoA 兩輪、train-ticket（53 節點）一輪，各 12 題零編造；runtime 模式待機器 A 回來"),
    ("sub", "業務流程問題"), ("b", "「訂票流程會經過哪些服務」這種不在圖上，它用「推測」語氣答，節點都存在但語氣是猜的；它有說要驅動流量才準")], size=13)
notes(s, "第一條是設計取捨：把「想」限制在選查詢，代價是選錯就漏答。我認為漏答比編造好，這是刻意的。")

# ---------- 13 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 改進歷程", 1, 13)
bw = Inches(3.5); gap = Inches(0.55); y0 = Inches(1.5); bh = Inches(3.5)
x1 = L + Inches(0.1); x2 = x1 + bw + gap; x3 = x2 + bw + gap
box(s, x1, y0, bw, bh, "regex 對算子", ["「影響」→ impact-of", "「順序」→ deploy-order", "", "第一輪 BoA 12 題就有 2 句是規則沒列到的說法，全靠 LLM 接。", "", "❓問題：窮舉，換個講法就漏"], label="v1 · 關鍵字規則")
arrow(s, x1 + bw + Inches(0.05) - Inches(0.25), y0 + Inches(1.45), "換成")
box(s, x2, y0, bw, bh, "例句向量比對", ["每個意圖十來句中英例句，問句向量算 cosine。", "", "第一次校準 33 句對 23 句。", "", "❓問題：帶服務名的問句分數普遍低 0.1–0.2，錯的幾乎都是這類"], label="v2 · 語意路由")
arrow(s, x2 + bw + Inches(0.05) - Inches(0.25), y0 + Inches(1.45), "變成")
box(s, x3, y0, bw, bh, "服務名先換成 X／Y", ["「frontend 依賴誰」→「X 依賴誰」再比對，和例句同形。", "", "第二次校準 33 句全對、有把握但錯 0。", "", "門檻依實測分數定：0.58 / 0.04 / 0.85"], hot=True, label="v3 · 遮名＋校準")
textbox(s, L, Inches(5.25), FULL, Inches(1.5), [
    ("sub", "同步修掉的（都是真環境逼出來的）"),
    ("p", "Spring 多建構子未標 @Autowired 導致 bot 假活 ・ greenfield 把「未量測」講成覆蓋率 0% ・ 「未部署」多猜 StatefulSet ・ 部署順序把 github.com 排第一 ・ 同一 thread 兩題並發寫同一檔 ・ thread 補 injection 檢查、LLM 呼叫補 timeout ・ 中文回答改台灣用語")], size=12.5)
notes(s, "方法論：不是一次設計好，而是每輪真環境跑完把錯的變成規則或例句。v1 到 v2 是換方法，v2 到 v3 是校準數據告訴我病在哪。")

# ---------- 14 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 改進歷程", 2, 14)
textbox(s, L, TOP, FULL, Inches(0.5), [("sec", "成果進步：路由命中翻倍、錯誤歸零、零編造從頭到尾")])
table(s, L, Inches(1.45), FULL, [
    ["指標", "v1 關鍵字規則", "v2 語意路由", "v3 遮名＋校準"],
    ["校準 33 句 hold-out 正確", "—", "23 / 33", "{33 / 33}"],
    ["校準：有把握但錯", "—", "2", "{0}"],
    ["校準：有把握且對", "—", "18", "{22}"],
    ["BoA 12 題：路由有把握且對", "4", "—", "{8}"],
    ["BoA 12 題：路由有把握但錯", "1", "—", "{0}"],
    ["BoA 12 題：交給 LLM planner", "7", "—", "5（皆該交）"],
    ["train-ticket 12 題（53 節點）", "—", "—", "路由 10 對、LLM 2 對、{0 錯}"],
    ["編造的答案（三輪 36 題）", "0", "0", "{0}"]], widths=[36, 21, 21, 22], size=12.5, center_cols=(1, 2, 3))
textbox(s, L, Inches(5.05), FULL, Inches(1.6), [
    ("sub", "同一句話，v2 → v3 的分數"),
    ("p", "「frontend 依賴誰」0.56 → {0.98}　·　「誰在用 balancereader」0.36 → {0.96}　·　「frontend 到 ledger-db 中間經過哪些服務」0.59（錯）→ {0.65（對）}")], size=13)
notes(s, "三個數字最重要：33/33、有把握但錯 0、編造 0。誠實補一句：33 句裡有 5 句後來被加進例句，真 hold-out 是 28 句，下次校準會換一批。")

# ---------- 15 ----------
s = prs.slides.add_slide(BLANK); frame(s, "[碩論] 驗證與下一步", None, 15)
textbox(s, L, TOP, COL, Inches(5.8), [
    ("sec", "驗證怎麼做"),
    ("b", "測試問題集七組（`docs/report-qa-test-questions.md`），先問最小集 12 題，每條機制各驗一次"),
    ("b", "每題記三欄：問題／log 裡選的算子與分數／答案對錯。錯分**編造**（最嚴重）與**漏答**"),
    ("b", "最重要的一組是「不能編造」：問不存在的服務、問 QPS、問資料庫版本"),
    ("b", "校準測試對真實 embedding 模型跑 hold-out 句，斷言「有把握但錯」為 0")], size=13)
textbox(s, RIGHT, TOP, COL, Inches(5.8), [
    ("sec", "下一步"),
    ("b", "機器 A 回來後跑 runtime 版 12 題，補有流量時的表現"),
    ("b", "下次校準換一批新的 hold-out 句"),
    ("b", "路由不確定的問法收集起來，補成例句"),
    ("b", "圖層清噪音節點：{postgresql}、{ts-common}、{rest-service-external}"),
    ("b", "把 `deploy-order` 接到 DeployPlanner，讓「對話」真的走到部署生成"),
    ("p", ""),
    ("p", "一句話：報告產完不再是終點。讀者可以在它底下追問，答案來自同一張圖、同一份證據，程式碼負責事實，模型負責把它講清楚。")], size=13)
notes(s, "結尾回到老師的問題：可用 RAG，我們做了，而且做成兩路。請老師看一下算子清單有沒有想加的問法，那是最容易擴充的地方。")

prs.save(OUT)
print("saved", OUT, os.path.getsize(OUT) // 1024, "KB", len(prs.slides), "slides")
