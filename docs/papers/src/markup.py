"""Tiny markup parser shared by both paper builders."""
import re

def parse(path):
    lines = open(path, encoding='utf8').read().split('\n')
    meta = {'author': [], 'affil': []}
    blocks = []
    i = 0
    para = []

    def flush():
        if para:
            blocks.append(('p', ' '.join(para).strip()))
            para.clear()

    while i < len(lines):
        ln = lines[i]
        s = ln.strip()
        if not s:
            flush(); i += 1; continue
        if s.startswith('!table'):
            flush()
            cap = s[len('!table'):].strip()
            widths = None; rows = []
            i += 1
            while not lines[i].strip().startswith('!end'):
                t = lines[i].strip()
                if t.startswith('!widths'):
                    widths = [int(x) for x in t.split()[1].split(',')]
                elif t.startswith('|'):
                    rows.append([c.strip() for c in t.strip('|').split(' | ')])
                i += 1
            blocks.append(('table', cap, widths, rows)); i += 1; continue
        if s.startswith('!code'):
            flush(); code = []; i += 1
            while not lines[i].strip().startswith('!endcode'):
                code.append(lines[i]); i += 1
            blocks.append(('code', s[len('!code'):].strip(), code)); i += 1; continue
        m = re.match(r'!(\w+)\s*(.*)', s)
        if m:
            flush()
            k, v = m.group(1), m.group(2)
            if k in ('author', 'affil'):
                meta[k].append(v)
            elif k == 'fig':
                p = [x.strip() for x in v.split('|')]
                blocks.append(('fig', p[0], p[1], float(p[2]) if len(p) > 2 else 5.0))
            elif k in ('bib', 'refhead', 'algo'):
                blocks.append((k, v))
            else:
                meta[k] = v
            i += 1; continue
        if s.startswith('### '):
            flush(); blocks.append(('h3', s[4:])); i += 1; continue
        if s.startswith('## '):
            flush(); blocks.append(('h2', s[3:])); i += 1; continue
        if s.startswith('# '):
            flush(); blocks.append(('h1', s[2:])); i += 1; continue
        if s.startswith('> '):
            flush(); blocks.append(('quote', s[2:])); i += 1; continue
        if s.startswith('- '):
            flush(); items = []
            while i < len(lines) and lines[i].strip().startswith('- '):
                items.append(lines[i].strip()[2:]); i += 1
            blocks.append(('bullets', items)); continue
        if re.match(r'\d+\. ', s):
            flush(); items = []
            while i < len(lines) and re.match(r'\d+\. ', lines[i].strip()):
                items.append(re.sub(r'^\d+\. ', '', lines[i].strip())); i += 1
            blocks.append(('numbered', items)); continue
        para.append(s); i += 1
    flush()
    return meta, blocks


def inline(text):
    """Split **bold** and `code` into (kind, text) segments."""
    out = []
    for part in re.split(r'(\*\*.+?\*\*|`[^`]+`|⟦[^⟧]+⟧|\*[^*\s][^*]*\*)', text):
        if not part:
            continue
        if part.startswith('**'):
            out.append(('b', part[2:-2]))
        elif part.startswith('`'):
            out.append(('c', part[1:-1]))
        elif part.startswith('⟦'):
            out.append(('a', part[1:-1]))      # internal link: "anchor|label"
        elif part.startswith('*'):
            out.append(('i', part[1:-1]))
        else:
            out.append(('t', part))
    return out


def esc(t):
    return t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
