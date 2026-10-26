"""Build the COMPSAC paper as an IEEE conference-style (US Letter, two-column) Word file."""
import os, re, shutil, sys, subprocess
from markup import parse, esc
from ooxml import run, runs, para, drawing, table, bookmark

import tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
FIG = os.path.join(HERE, 'fig')
SRC = os.path.join(HERE, 'compsac.txt')
DEST = os.path.abspath(sys.argv[1])
OUT = os.path.join(tempfile.mkdtemp(prefix='compsac_'), 'pkg')
CJK = 'PMingLiU'
TNR = 'Times New Roman'

meta, blocks = parse(SRC)

# ---- citations: number by first appearance, IEEE style ----
bib = {}
bib_order = []
for b in blocks:
    if b[0] == 'bib':
        k, txt = [x.strip() for x in b[1].split('|', 1)]
        bib[k] = txt
cite_no = {}


def cite(text):
    def rep(m):
        keys = [k.strip().lstrip('@') for k in m.group(1).split(';')]
        nums = []
        for k in keys:
            if k not in bib:
                raise SystemExit(f'unknown citation {k}')
            if k not in cite_no:
                cite_no[k] = len(cite_no) + 1
                bib_order.append(k)
            nums.append((k, cite_no[k]))
        return ', '.join(f'[⟦{k}|{n}⟧]' for k, n in nums)
    return re.sub(r'\[(@[^\]]+)\]', rep, text)


def roman(n):
    vals = [(10, 'X'), (9, 'IX'), (5, 'V'), (4, 'IV'), (1, 'I')]
    s = ''
    for v, r in vals:
        while n >= v:
            s += r; n -= v
    return s


# ---- styles ----
def rpr(size, extra=''):
    return (f'<w:rPr><w:rFonts w:ascii="{TNR}" w:hAnsi="{TNR}" w:eastAsia="{CJK}" w:cs="{TNR}"/>'
            f'{extra}<w:sz w:val="{size}"/><w:szCs w:val="{size}"/></w:rPr>')


def pstyle(sid, name, ppr, rp, based='Normal', nxt=None):
    n = f'<w:next w:val="{nxt}"/>' if nxt else ''
    return (f'<w:style w:type="paragraph" w:customStyle="1" w:styleId="{sid}"><w:name w:val="{name}"/>'
            f'<w:basedOn w:val="{based}"/>{n}<w:qFormat/><w:pPr>{ppr}</w:pPr>{rp}</w:style>')


styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
          f'<w:docDefaults><w:rPrDefault>{rpr(20, "<w:lang w:val=\"en-US\" w:eastAsia=\"zh-TW\"/>")}</w:rPrDefault>'
          '<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>'
          '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/>'
          '<w:pPr><w:widowControl/><w:jc w:val="both"/></w:pPr>' + rpr(20) + '</w:style>'
          + pstyle('PaperTitle', 'Paper Title', '<w:spacing w:after="80"/><w:jc w:val="center"/>', rpr(48))
          + pstyle('PaperSubtitle', 'Paper Subtitle', '<w:spacing w:after="200"/><w:jc w:val="center"/>', rpr(24, '<w:i/>'))
          + pstyle('Author', 'Author', '<w:spacing w:before="40"/><w:jc w:val="center"/>', rpr(22))
          + pstyle('Affiliation', 'Affiliation', '<w:spacing w:after="240"/><w:jc w:val="center"/>', rpr(20, '<w:i/>'))
          + pstyle('Abstract', 'Abstract', '<w:spacing w:after="120"/><w:ind w:firstLine="202"/><w:jc w:val="both"/>', rpr(18, '<w:b/>'))
          + pstyle('Keywords', 'Keywords', '<w:spacing w:after="160"/><w:ind w:firstLine="202"/><w:jc w:val="both"/>', rpr(18, '<w:b/>'))
          + pstyle('BodyText', 'Body Text', '<w:ind w:firstLine="288"/><w:jc w:val="both"/>', rpr(20))
          + pstyle('Heading1', 'heading 1', '<w:keepNext/><w:numPr><w:numId w:val="1"/></w:numPr><w:spacing w:before="160" w:after="80"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/>', rpr(20, '<w:smallCaps/>'), nxt='BodyText')
          + pstyle('Heading2', 'heading 2', '<w:keepNext/><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr><w:spacing w:before="120" w:after="60"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/>', rpr(20, '<w:i/>'), nxt='BodyText')
          + pstyle('HeadingUnnum', 'Heading Unnumbered', '<w:keepNext/><w:spacing w:before="160" w:after="80"/><w:jc w:val="center"/>', rpr(20, '<w:smallCaps/>'))
          + pstyle('ListItem', 'List Item', '<w:ind w:left="288" w:hanging="216"/><w:jc w:val="both"/>', rpr(20))
          + pstyle('TableCaption', 'Table Caption', '<w:keepNext/><w:spacing w:before="120" w:after="60"/><w:jc w:val="center"/>', rpr(16, '<w:smallCaps/>'))
          + pstyle('TableCell', 'Table Cell', '<w:jc w:val="left"/>', rpr(16))
          + pstyle('FigureImage', 'Figure Image', '<w:keepNext/><w:spacing w:before="120" w:after="40"/><w:jc w:val="center"/>', rpr(20))
          + pstyle('FigureCaption', 'Figure Caption', '<w:spacing w:after="120"/><w:jc w:val="both"/>', rpr(16))
          + pstyle('AlgoTitle', 'Algorithm Title', '<w:keepNext/><w:spacing w:before="120"/><w:pBdr><w:top w:val="single" w:sz="8" w:space="1" w:color="000000"/><w:bottom w:val="single" w:sz="4" w:space="1" w:color="000000"/></w:pBdr>', rpr(16, '<w:b/>'))
          + pstyle('AlgoLine', 'Algorithm Line', '<w:keepNext/><w:keepLines/><w:ind w:left="200" w:hanging="200"/><w:jc w:val="left"/>', rpr(15))
          + pstyle('AlgoLast', 'Algorithm Last Line', '<w:ind w:left="200" w:hanging="200"/><w:spacing w:after="120"/><w:pBdr><w:bottom w:val="single" w:sz="8" w:space="1" w:color="000000"/></w:pBdr><w:jc w:val="left"/>', rpr(15))
          + pstyle('Reference', 'Reference', '<w:ind w:left="360" w:hanging="360"/><w:spacing w:after="20"/><w:jc w:val="left"/>', rpr(16))
          + '<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>'
          '<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>'
          '</w:styles>')

numbering = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
             '<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="multilevel"/>'
             '<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="upperRoman"/><w:pStyle w:val="Heading1"/><w:lvlText w:val="%1."/><w:lvlJc w:val="center"/><w:suff w:val="space"/><w:pPr><w:ind w:left="0" w:firstLine="0"/></w:pPr></w:lvl>'
             '<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="upperLetter"/><w:pStyle w:val="Heading2"/><w:lvlText w:val="%2."/><w:lvlJc w:val="left"/><w:suff w:val="space"/><w:pPr><w:ind w:left="0" w:firstLine="0"/></w:pPr></w:lvl>'
             '</w:abstractNum>'
             '<w:abstractNum w:abstractNumId="2"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="288" w:hanging="216"/></w:pPr></w:lvl></w:abstractNum>'
             '<w:abstractNum w:abstractNumId="3"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1)"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="288" w:hanging="288"/></w:pPr></w:lvl></w:abstractNum>'
             '<w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num>'
             '<w:num w:numId="2"><w:abstractNumId w:val="2"/></w:num>'
             'NUMS</w:numbering>')
num_extra = []

# ---- body ----
body = []
rels = []


def ext_link(url):
    rid = f'rIdLink{len(rels) + 1}'
    rels.append(f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="{esc(url)}" TargetMode="External"/>')
    return rid

cnt = {'fig': 0, 'tab': 0, 'pic': 100, 'num': 10}
prev = None

body.append(para('PaperTitle', run(meta['title'])))
body.append(para('PaperSubtitle', run(meta['subtitle'])))
if meta['author']:
    body.append(para('Author', run(meta['author'][0])))
# first section (one column) ends here
sect1 = ('<w:sectPr><w:pgSz w:w="12240" w:h="15840"/>'
         '<w:pgMar w:top="1080" w:right="900" w:bottom="1440" w:left="900" w:header="720" w:footer="720" w:gutter="0"/>'
         '<w:cols w:space="360"/></w:sectPr>')
if meta['affil']:
    body.append(f'<w:p><w:pPr><w:pStyle w:val="Affiliation"/>{sect1}</w:pPr>{run(meta["affil"][0])}</w:p>')
else:
    body.append(f'<w:p><w:pPr><w:pStyle w:val="Affiliation"/><w:spacing w:after="120"/>{sect1}</w:pPr></w:p>')

body.append(para('Abstract', run('摘要—', extra='<w:i/>') + runs(cite(meta['abstract']))))
body.append(para('Keywords', run('Index Terms—', extra='<w:i/>') + run(meta['keywords'])))

for b in blocks:
    k = b[0]
    if k == 'h1':
        body.append(para('Heading1', run(b[1])))
    elif k == 'h2':
        body.append(para('Heading2', run(b[1])))
    elif k == 'p':
        body.append(para('BodyText', runs(cite(b[1]))))
    elif k == 'bullets':
        for it in b[1]:
            body.append(para('ListItem', runs(cite(it)), '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>'))
    elif k == 'numbered':
        cnt['num'] += 1
        nid = cnt['num']
        num_extra.append(f'<w:num w:numId="{nid}"><w:abstractNumId w:val="3"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>')
        for it in b[1]:
            body.append(para('ListItem', runs(cite(it)), f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{nid}"/></w:numPr><w:ind w:left="288" w:hanging="288"/>'))
    elif k == 'table':
        cnt['tab'] += 1
        _, cap, widths, rows = b
        body.append(para('TableCaption', run(f'表 {roman(cnt["tab"])}　{cap}')))
        body.append(table([[cite(c) for c in r] for r in rows], widths, 'TableCell'))
        body.append(para('BodyText', '', '<w:spacing w:after="80"/>'))
    elif k == 'fig':
        cnt['fig'] += 1; cnt['pic'] += 1
        _, fname, cap, wid = b
        src = os.path.join(FIG, fname)
        rid = f'rIdFig{cnt["fig"]}'
        rels.append(f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{fname}"/>')
        body.append(para('FigureImage', drawing(rid, cnt['pic'], fname, wid, src, cap)))
        body.append(para('FigureCaption', runs(f'圖 {cnt["fig"]}.　{cite(cap)}')))
    elif k == 'algo':
        body.append(para('AlgoTitle', run(b[1])))
    elif k == 'code':
        lines = b[2]
        for i, ln in enumerate(lines):
            style = 'AlgoLast' if i == len(lines) - 1 else 'AlgoLine'
            body.append(para(style, run(ln)))
    elif k == 'refhead':
        if 'ack' in meta:
            body.append(para('HeadingUnnum', run('致謝')))
            body.append(para('BodyText', run(meta['ack'])))
        body.append(para('HeadingUnnum', run('參考文獻')))
        for i, key in enumerate(bib_order, 1):
            body.append(para('Reference', bookmark(f'ref_{key}', 900 + i, run(f'[{i}]\t') + runs(bib[key], link=ext_link)), '<w:tabs><w:tab w:val="left" w:pos="360"/></w:tabs>'))
        unused = [x for x in bib if x not in cite_no]
        if unused:
            print('WARNING uncited:', unused)
    prev = k

sect2 = ('<w:sectPr><w:type w:val="continuous"/><w:pgSz w:w="12240" w:h="15840"/>'
         '<w:pgMar w:top="1080" w:right="900" w:bottom="1440" w:left="900" w:header="720" w:footer="720" w:gutter="0"/>'
         '<w:cols w:num="2" w:space="360"/></w:sectPr>')

NS = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
      'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
      'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"')
document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {NS}><w:body>'
            + ''.join(body) + sect2 + '</w:body></w:document>')

shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(os.path.join(OUT, 'word', '_rels'))
os.makedirs(os.path.join(OUT, 'word', 'media'))
os.makedirs(os.path.join(OUT, '_rels'))
os.makedirs(os.path.join(OUT, 'docProps'))
for b in blocks:
    if b[0] == 'fig':
        shutil.copy(os.path.join(FIG, b[1]), os.path.join(OUT, 'word', 'media', b[1]))


def w(p, s):
    open(os.path.join(OUT, p), 'w', encoding='utf8').write(s)


w('word/document.xml', document)
w('word/styles.xml', styles)
w('word/numbering.xml', numbering.replace('NUMS', ''.join(num_extra)))
w('word/settings.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:defaultTabStop w:val="360"/><w:characterSpacingControl w:val="compressPunctuation"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>')
w('word/_rels/document.xml.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
  '<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
  '<Relationship Id="rIdNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>'
  '<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>'
  + ''.join(rels) + '</Relationships>')
w('_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
  '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>')
w('docProps/core.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">'
  f'<dc:title>{esc(meta["title"])}</dc:title><dc:creator></dc:creator></cp:coreProperties>')
w('[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
  '<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>'
  '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
  '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
  '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>'
  '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>'
  '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>')

if os.path.exists(DEST):
    os.remove(DEST)
import zipfile
with zipfile.ZipFile(DEST, 'w', zipfile.ZIP_DEFLATED) as zf:
    names = []
    for dp, _, fs in os.walk(OUT):
        for fn in fs:
            full = os.path.join(dp, fn)
            names.append((os.path.relpath(full, OUT).replace(os.sep, '/'), full))
    names.sort(key=lambda x: (x[0] != '[Content_Types].xml', x[0]))
    for arc, full in names:
        zf.write(full, arc)
print('wrote', DEST, 'figs', cnt['fig'], 'tables', cnt['tab'], 'refs', len(bib_order))
