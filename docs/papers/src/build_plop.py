"""Build the AsianPLoP paper on top of the official ACM single-column (PLoP) Word template."""
import os, re, shutil, sys, subprocess
from markup import parse, esc
from ooxml import run, runs, para, drawing, table, bookmark

import tempfile, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
FIG = os.path.join(HERE, 'fig')
SRC = os.path.join(HERE, 'plop.txt')
DEST = os.path.abspath(sys.argv[1])
NPAGES = sys.argv[2] if len(sys.argv) > 2 else 'X'
OUT_DIR = os.path.join(tempfile.mkdtemp(prefix='plop_'), 'pkg')
TPL = os.path.join(os.path.dirname(OUT_DIR), 'tpl')
with zipfile.ZipFile(os.path.join(HERE, 'acm_submission_template.docx')) as z:
    z.extractall(TPL)

CJK = 'PMingLiU'

shutil.rmtree(OUT_DIR, ignore_errors=True)
shutil.copytree(TPL, OUT_DIR)
W = lambda p: os.path.join(OUT_DIR, p)

# --- styles: Chinese body font, zh-TW language ---
st = open(W('word/styles.xml'), encoding='utf8').read()
st = st.replace('w:eastAsia="Cambria"', f'w:eastAsia="{CJK}"').replace('w:eastAsia="Times New Roman"', f'w:eastAsia="{CJK}"')
st = st.replace('<w:rFonts w:asciiTheme="minorHAnsi" w:eastAsiaTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi" w:cstheme="minorBidi"/>',
                f'<w:rFonts w:asciiTheme="minorHAnsi" w:eastAsia="{CJK}" w:hAnsiTheme="minorHAnsi" w:cstheme="minorBidi"/>')
st = re.sub(r'w:eastAsia="(ja-JP|en-US)"', 'w:eastAsia="zh-TW"', st)
open(W('word/styles.xml'), 'w', encoding='utf8').write(st)

# --- numbering: one bullet list + a fresh decimal list per numbered block ---
nb = open(W('word/numbering.xml'), encoding='utf8').read()
bullet_abs = ('<w:abstractNum w:abstractNumId="90"><w:multiLevelType w:val="singleLevel"/>'
              '<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/>'
              '<w:pPr><w:ind w:left="480" w:hanging="240"/></w:pPr><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:hint="default"/></w:rPr></w:lvl></w:abstractNum>')
nb = re.sub(r'(<w:num w:numId=)', bullet_abs + r'\1', nb, count=1)
num_extra = ['<w:num w:numId="900"><w:abstractNumId w:val="90"/></w:num>']

meta, blocks = parse(SRC)

# ---- citations: [@key] -> ACM author-year labels, e.g. [Schneider et al. 2025]; references unnumbered, alphabetical ----
bib_keys, bib = [], {}
for b in blocks:
    if b[0] == 'bib':
        k, t = [x.strip() for x in b[1].split('|', 1)]
        bib_keys.append(k); bib[k] = t


def _label(text):
    m = re.match(r'^(.+?)\. (\d{4})\. ', text)
    if m:
        names = re.split(r', and |, | and ', m.group(1))
        def _surname(n):
            w = n.split()
            return ' '.join(w[-2:]) if len(w) > 2 and w[-2] in ('Al', 'van', 'Van', 'de', 'De', 'Di', 'von', 'van der') else w[-1]
        sur = [_surname(n) for n in names]
        if len(sur) == 1:
            who = sur[0]
        elif len(sur) == 2:
            who = f'{sur[0]} and {sur[1]}'
        else:
            who = f'{sur[0]} et al.'
        return who, m.group(2)
    org = text.split('. ')[0]
    y = re.search(r'(?:accessed|Retrieved \w+ \d+,) (\d{4})', text)
    return org, (y.group(1) if y else 'n.d.')


labels = {k: _label(bib[k]) for k in bib_keys}
# same author+year twice -> 2026a / 2026b (in alphabetical order of the entry text)
groups = {}
for k in sorted(bib_keys, key=lambda k: bib[k].lower()):
    groups.setdefault(labels[k], []).append(k)
label_text = {}
for (who, yr), ks in groups.items():
    for i, k in enumerate(ks):
        suffix = chr(ord('a') + i) if len(ks) > 1 else ''
        label_text[k] = f'{who} {yr}{suffix}'

cited = set()
def _scan(t):
    for m in re.finditer(r'\[(@[^\]]+)\]', t):
        for k in m.group(1).split(';'):
            k = k.strip().lstrip('@')
            if k not in bib:
                raise SystemExit(f'unknown citation {k}')
            cited.add(k)
for b in blocks:
    if b[0] in ('p', 'quote'):
        _scan(b[1])
    elif b[0] in ('bullets', 'numbered'):
        for it in b[1]: _scan(it)
    elif b[0] == 'table':
        for r in b[3]:
            for c in r: _scan(c)
order = sorted([k for k in bib_keys if k in cited], key=lambda k: (label_text[k].lower(), bib[k].lower()))
uncited = [k for k in bib_keys if k not in cited]
if uncited:
    print('WARNING uncited:', uncited)
def cite(t):
    return re.sub(r'\[(@[^\]]+)\]', lambda m: '[' + '; '.join('⟦' + k.strip().lstrip('@') + '|' + label_text[k.strip().lstrip('@')] + '⟧' for k in m.group(1).split(';')) + ']', t)

body = []
rels = []


def ext_link(url):
    rid = f'rIdLink{len(rels) + 1}'
    rels.append(f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="{esc(url)}" TargetMode="External"/>')
    return rid

counters = {'fig': 0, 'tab': 0, 'num': 900, 'pic': 100}
prev = None

# --- front matter ---
fn_mark = '<w:r><w:footnoteReference w:customMarkFollows="1" w:id="1"/><w:t>*</w:t></w:r>'
body.append(para('Titledocument', run(meta['title']) + ('' if meta['author'] else fn_mark)))
body.append(para('ShortTitle', run(meta['shorttitle'])))
body.append(para('Subtitle', run(meta['subtitle'])))
for k, (a, f) in enumerate(zip(meta['author'], meta['affil'])):
    fn = '<w:r><w:rPr><w:caps w:val="0"/></w:rPr><w:footnoteReference w:customMarkFollows="1" w:id="1"/><w:t>*</w:t></w:r>' if k == 0 else ''
    body.append(para('Authors', run(a, extra='<w:caps w:val="0"/>') + fn))
    body.append(para('Affiliation', run(f)))
body.append(para('Abstract', run(meta['abstract'])))
if meta.get('abstracten'):
    body.append(para('Abstract', run('Abstract. ', bold=True) + run(meta['abstracten'])))
body.append(para('CCSDescription', run('CCS CONCEPTS ', bold=True) + run('• ' + meta['ccs']), '<w:jc w:val="left"/>'))
body.append(para('KeyWords', run('Additional Keywords and Phrases: ', bold=True) + run(meta['keywords'])))
body.append(para('ACMRefHead', run('ACM Reference Format:')))
body.append(para('ACMRef', run(meta['acmref'].replace('NPAGES', NPAGES))))

for b in blocks:
    kind = b[0]
    if kind == 'h1':
        body.append(para('Head1', run(b[1]), '<w:tabs><w:tab w:val="clear" w:pos="360"/></w:tabs><w:ind w:left="432" w:hanging="432"/>'))
    elif kind == 'h2':
        body.append(para('Head2', run(b[1]), '<w:keepNext/><w:tabs><w:tab w:val="clear" w:pos="360"/></w:tabs>'))
    elif kind == 'h3':
        body.append(para('Head3', run(b[1])))
    elif kind == 'p':
        style = 'PostHeadPara' if prev in ('h1', 'h2', 'h3', 'fig', 'table') else 'Para'
        body.append(para(style, runs(cite(b[1]))))
    elif kind == 'quote':
        body.append(para('Extract', runs(cite(b[1]), bold_all=True), '<w:keepLines/>'))
    elif kind == 'bullets':
        for it in b[1]:
            body.append(para('ListParagraph', runs(cite(it)), '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="900"/></w:numPr><w:spacing w:before="40" w:after="40"/>'))
    elif kind == 'numbered':
        counters['num'] += 1
        nid = counters['num']
        num_extra.append(f'<w:num w:numId="{nid}"><w:abstractNumId w:val="3"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>')
        for it in b[1]:
            body.append(para('ListParagraph', runs(cite(it)), f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{nid}"/></w:numPr><w:spacing w:before="40" w:after="40"/>'))
    elif kind == 'table':
        counters['tab'] += 1
        _, cap, widths, rows = b
        body.append(para('TableCaption', run(f'表 {counters["tab"]}：{cap}'), '<w:keepNext/>'))
        body.append(table([[cite(c) for c in r] for r in rows], widths, 'TableCell'))
        body.append(para('Para', '', '<w:spacing w:after="60"/>'))
    elif kind == 'fig':
        counters['fig'] += 1; counters['pic'] += 1
        _, fname, cap, wid = b
        src = os.path.join(FIG, fname)
        shutil.copy(src, W('word/media/' + fname))
        rid = f'rIdFig{counters["fig"]}'
        rels.append(f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{fname}"/>')
        body.append(para('Image', drawing(rid, counters['pic'], fname, wid, src, cap), '<w:keepNext/><w:jc w:val="center"/>'))
        body.append(para('FigureCaption', run(f'圖 {counters["fig"]}：{cap}')))
    elif kind == 'refhead':
        if meta.get('ack'):
            body.append(para('AckHead', run('致謝（ACKNOWLEDGMENTS）')))
            body.append(para('AckPara', runs(meta['ack'])))
        body.append(para('ReferenceHead', run('參考文獻（REFERENCES）')))
        for i, k in enumerate(order):
            body.append(para('Bibentry', bookmark(f'ref_{k}', 900 + i, runs(bib[k], link=ext_link)),
                             '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="0"/></w:numPr><w:ind w:left="360" w:hanging="360"/><w:jc w:val="left"/>'))
    prev = kind

# --- assemble document.xml with the template's root element and section properties ---
doc = open(W('word/document.xml'), encoding='utf8').read()
root_open = doc[:doc.find('<w:body>') + len('<w:body>')]
sect = re.findall(r'<w:sectPr.*?</w:sectPr>', doc, re.S)[-1]
open(W('word/document.xml'), 'w', encoding='utf8').write(root_open + ''.join(body) + sect + '</w:body></w:document>')

nb = nb.replace('</w:numbering>', ''.join(num_extra) + '</w:numbering>')
open(W('word/numbering.xml'), 'w', encoding='utf8').write(nb)

rl = open(W('word/_rels/document.xml.rels'), encoding='utf8').read()
rl = rl.replace('</Relationships>', ''.join(rels) + '</Relationships>')
open(W('word/_rels/document.xml.rels'), 'w', encoding='utf8').write(rl)

ct = open(W('[Content_Types].xml'), encoding='utf8').read()
if 'Extension="png"' not in ct:
    ct = ct.replace('<Default ', '<Default Extension="png" ContentType="image/png"/><Default ', 1)
    open(W('[Content_Types].xml'), 'w', encoding='utf8').write(ct)

# --- first-page copyright footnote: AsianPLoP 2027 placeholders ---
fn = open(W('word/footnotes.xml'), encoding='utf8').read()
fn = fn.replace('the 11th Asian Conference', 'the 13th Asian Conference')
fn = fn.replace('© 2025', '© 2027')
fn = fn.replace('<w:t>AsianPLoP’</w:t></w:r><w:r><w:t>25</w:t></w:r>', '<w:t>AsianPLoP’</w:t></w:r><w:r><w:t>27</w:t></w:r>')
fn = fn.replace(', March 28–31, Taichung, Taiwan. Copyright 2025 is held by the author(s). ISBN 978-1-941652-21-3',
                ', March 26–29, Keelung, Taiwan. Copyright 2027 is held by the author(s). ISBN 〔TBA〕')
open(W('word/footnotes.xml'), 'w', encoding='utf8').write(fn)

# --- document properties ---
core = open(W('docProps/core.xml'), encoding='utf8').read()
core = re.sub(r'<dc:title>.*?</dc:title>', f'<dc:title>{esc(meta["title"])}</dc:title>', core)
if '<dc:title>' not in core:
    core = core.replace('</cp:coreProperties>', f'<dc:title>{esc(meta["title"])}</dc:title></cp:coreProperties>')
core = re.sub(r'<dc:creator>.*?</dc:creator>', '<dc:creator></dc:creator>', core)
core = re.sub(r'<cp:lastModifiedBy>.*?</cp:lastModifiedBy>', '<cp:lastModifiedBy></cp:lastModifiedBy>', core)
open(W('docProps/core.xml'), 'w', encoding='utf8').write(core)

# drop the template's sample images (no longer referenced)
rl = open(W('word/_rels/document.xml.rels'), encoding='utf8').read()
for m in re.findall(r'<Relationship Id="[^"]+" Type="[^"]+/image" Target="(media/image\d\.\w+)"/>', rl):
    rl = re.sub(r'<Relationship Id="[^"]+" Type="[^"]+/image" Target="%s"/>' % re.escape(m), '', rl)
    os.remove(W('word/' + m))
open(W('word/_rels/document.xml.rels'), 'w', encoding='utf8').write(rl)

if os.path.exists(DEST):
    os.remove(DEST)
import zipfile
with zipfile.ZipFile(DEST, 'w', zipfile.ZIP_DEFLATED) as zf:
    names = []
    for dp, _, fs in os.walk(OUT_DIR):
        for fn in fs:
            full = os.path.join(dp, fn)
            names.append((os.path.relpath(full, OUT_DIR).replace(os.sep, '/'), full))
    names.sort(key=lambda x: (x[0] != '[Content_Types].xml', x[0]))
    for arc, full in names:
        zf.write(full, arc)
print('wrote', DEST, 'figures', counters['fig'], 'tables', counters['tab'])
