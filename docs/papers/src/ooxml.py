"""OOXML fragments shared by both builders."""
import re
import struct
from markup import inline, esc

EMU = 914400


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    w, h = struct.unpack('>II', head[16:24])
    return w, h


def run(text, bold=False, code=False, size=None, extra='', italic=False, style=None):
    rpr = f'<w:rStyle w:val="{style}"/>' if style else ''
    if code:
        rpr += '<w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/>'
    if bold:
        rpr += '<w:b/><w:bCs/>'
    if italic:
        rpr += '<w:i/><w:iCs/>'
    if size:
        rpr += f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>'
    rpr += extra
    rpr = f'<w:rPr>{rpr}</w:rPr>' if rpr else ''
    return f'<w:r>{rpr}<w:t xml:space="preserve">{esc(text)}</w:t></w:r>'


URL_RE = re.compile(r'(https?://[^\s;]+?|doi: 10\.\S+?)(?=[.,;]?(\s|$))')


def runs(text, bold_all=False, size=None, link=None):
    """Render inline markup. `link(url) -> rId` turns URLs and DOIs into clickable hyperlinks."""
    out = []
    for k, t in inline(text):
        b = (k == 'b' or bold_all)
        if k == 'a':
            anchor, label = t.split('|', 1)
            out.append(f'<w:hyperlink w:anchor="ref_{anchor}" w:history="1">'
                       + run(label, bold=b, size=size) + '</w:hyperlink>')
            continue
        if link is None or k == 'c':
            out.append(run(t, bold=b, code=(k == 'c'), size=size, italic=(k == 'i')))
            continue
        pos = 0
        for m in URL_RE.finditer(t):
            if m.start() > pos:
                out.append(run(t[pos:m.start()], bold=b, size=size, italic=(k == 'i')))
            u = m.group(1)
            target = 'https://doi.org/' + u[5:] if u.startswith('doi: ') else u
            if u.startswith('doi: '):
                out.append(run('doi: ', bold=b, size=size))
                u = u[5:]
            out.append(f'<w:hyperlink r:id="{link(target)}" w:history="1">'
                       + run(u, bold=b, size=size, style='Hyperlink') + '</w:hyperlink>')
            pos = m.end()
        if pos < len(t):
            out.append(run(t[pos:], bold=b, size=size, italic=(k == 'i')))
    return ''.join(out)


def bookmark(name, bid, body):
    return f'<w:bookmarkStart w:id="{bid}" w:name="{name}"/>{body}<w:bookmarkEnd w:id="{bid}"/>'


def para(style, body, ppr_extra=''):
    return f'<w:p><w:pPr><w:pStyle w:val="{style}"/>{ppr_extra}</w:pPr>{body}</w:p>'


def drawing(rid, pid, name, w_in, path, descr):
    pw, ph = png_size(path)
    cx = int(w_in * EMU)
    cy = int(cx * ph / pw)
    return (f'<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">'
            f'<wp:extent cx="{cx}" cy="{cy}"/><wp:docPr id="{pid}" name="Picture {pid}" descr="{esc(descr)}"/>'
            f'<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/></wp:cNvGraphicFramePr>'
            f'<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
            f'<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="{pid}" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
            f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
            f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
            f'</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>')


def table(rows, widths, cell_style, size=None, header_shade=None):
    total = sum(widths)
    out = [f'<w:tbl><w:tblPr><w:tblW w:w="{total}" w:type="dxa"/><w:jc w:val="center"/>'
           '<w:tblBorders><w:top w:val="single" w:sz="6" w:space="0" w:color="000000"/>'
           '<w:bottom w:val="single" w:sz="6" w:space="0" w:color="000000"/></w:tblBorders>'
           '<w:tblLayout w:type="fixed"/>'
           '<w:tblCellMar><w:top w:w="20" w:type="dxa"/><w:left w:w="70" w:type="dxa"/><w:bottom w:w="20" w:type="dxa"/><w:right w:w="70" w:type="dxa"/></w:tblCellMar>'
           '</w:tblPr><w:tblGrid>']
    out += [f'<w:gridCol w:w="{w}"/>' for w in widths]
    out.append('</w:tblGrid>')
    for ri, row in enumerate(rows):
        hdr = ri == 0
        out.append('<w:tr>' + ('<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>' if hdr else '<w:trPr><w:cantSplit/></w:trPr>'))
        for ci, cell in enumerate(row):
            borders = '<w:tcBorders><w:bottom w:val="single" w:sz="4" w:space="0" w:color="000000"/></w:tcBorders>' if hdr else ''
            shade = f'<w:shd w:val="clear" w:color="auto" w:fill="{header_shade}"/>' if (hdr and header_shade) else ''
            out.append(f'<w:tc><w:tcPr><w:tcW w:w="{widths[ci]}" w:type="dxa"/>{borders}{shade}</w:tcPr>'
                       + para(cell_style, runs(cell, bold_all=hdr, size=size),
                              ('<w:keepNext/>' if ri < len(rows) - 1 else '') + '<w:ind w:firstLine="0"/><w:jc w:val="left"/>')
                       + '</w:tc>')
        out.append('</w:tr>')
    out.append('</w:tbl>')
    return ''.join(out)
