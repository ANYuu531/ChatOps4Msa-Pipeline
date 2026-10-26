"""Generate the four Solution flowcharts (p_flow1..4) on a fixed grid.

Each node sits in a (col, row) cell so the chart snakes across the page
instead of growing downward.  Run:  python3 flows.py  (needs Graphviz neato).
"""
import os, subprocess
HERE = os.path.dirname(os.path.abspath(__file__))
CW, RH = 138, 74          # cell width / row height in points

STYLE = {
    'term': 'shape=ellipse, style=filled, fillcolor=white',
    'step': 'shape=box, style="rounded,filled", fillcolor="#f4f4f4"',
    'pre':  'shape=box, style="rounded,filled,dashed", fillcolor="#fbfbfb"',
    'ask':  'shape=diamond, style=filled, fillcolor=white, margin="0.02,0.01"',
    'out':  'shape=box, style="rounded,dashed", fillcolor=white',
    'note': 'shape=plaintext, fontcolor="#555555", fontsize=9',
}

def chart(name, nodes, edges):
    out = ['digraph G {',
           ' graph [splines=true, outputorder=edgesfirst];',
           ' node [fontname="PingFang TC", fontsize=10, color="#777777", margin="0.08,0.04"];',
           ' edge [color="#555555", arrowsize=0.6, fontname="PingFang TC", fontsize=9, fontcolor="#555555"];']
    for nid, (kind, col, row, label) in nodes.items():
        out.append(f' {nid} [{STYLE[kind]}, label="{label}", pos="{col*CW},{-row*RH}!"];')
    for e in edges:
        a, b = e[0], e[1]
        lab = f' [label="{e[2]}"]' if len(e) > 2 and e[2] else ''
        out.append(f' {a}->{b}{lab};')
    out.append('}')
    dot = os.path.join(HERE, name + '.dot')
    open(dot, 'w', encoding='utf8').write('\n'.join(out) + '\n')
    subprocess.run(['neato', '-n2', '-Tpng', '-Gdpi=300', dot, '-o', os.path.join(HERE, name + '.png')], check=True)

# ① 混合重建：上排是前提（送流量的迴圈），下兩排是合併的五步
chart('p_flow1', {
    'note': ('note', 1.5, -0.55, '前提：先以流量走遍路徑（虛線框）'),
    'start': ('term', 0, 0, '三個來源\\n各自的結果'),
    't1': ('pre', 1, 0, '送流量（先走\\n主要使用者流程）'),
    't2': ('pre', 2, 0, '列出「靜態有、\\n執行期還沒量到」的邊'),
    't3': ('ask', 3, 0, '清單還在\\n縮短？'),
    't4': ('pre', 2, 1, '依清單設計下一輪請求\\n（需要人知道的值 → ③）'),
    's1': ('step', 4, 0, '1. 以部署描述檔的\\n服務清單建節點'),
    'd1': ('ask', 4, 1, '名稱對得上\\n部署裡的服務？'),
    'h3': ('out', 3, 1, '交給 ③'),
    's2': ('step', 4, 2, '2. 以（來源，目標）\\n為鍵合併各來源的邊'),
    's3': ('step', 3, 2, '3. 保留所有支持它的\\n來源與出處'),
    'd2': ('ask', 2, 2, '只有弱來源、\\n目標不在部署裡？'),
    's4': ('step', 2, 3, '4. 不新增節點，\\n只補強既有節點'),
    's5': ('step', 1, 2, '5. 記錄這次收集了\\n哪些來源（→ ④）'),
    'end': ('term', 0, 2, '每條邊都有\\n出處的圖（→ ②）'),
}, [('start','t1'),('t1','t2'),('t2','t3'),('t3','t4','是'),('t4','t1'),('t3','s1','否'),
    ('s1','d1'),('d1','h3','否'),('d1','s2','是'),('s2','s3'),('s3','d2'),('d2','s5','否'),('d2','s4','是'),('s4','s5'),('s5','end')])

# ② 實測優先於推測
chart('p_flow2', {
    'start': ('term', 0, 0, '合併後的一條邊\\n（或節點）'),
    'd1': ('ask', 1, 0, '執行期觀測\\n記錄到了？'),
    'a': ('step', 2, 0, '1. 標記為「實測」'),
    'b': ('step', 2, 1, '1. 標記為「推測」\\n（只來自程式碼、\\n設定或文件）'),
    's2': ('step', 3, 0, '2. 出處另存（可多筆），\\n標記只有一個'),
    's3': ('step', 3, 1.9, '3. 同一條邊有多個標記時\\n留較強的：實測取代推測'),
    's4': ('step', 2, 1.9, '4. 實測＝實線、推測＝虛線；\\n圖例跟著圖走'),
    's5': ('step', 1, 1.9, '5. 下游的摘要與報告\\n只能照抄或降級'),
    'end': ('term', 0, 1.9, '分得出哪條\\n可信的圖（→ ③）'),
}, [('start','d1'),('d1','a','是'),('d1','b','否'),('a','s2'),('b','s2'),('s2','s3'),('s3','s4'),('s4','s5'),('s5','end')])

# ③ 請人確認名稱對應
chart('p_flow3', {
    'start': ('term', 0, 0, '合併時遇到\\n的一個名稱'),
    'd1': ('ask', 1, 0, '規則能\\n對齊？'),
    'm': ('term', 1, 1, '合併'),
    'd2': ('ask', 2, 0, '這個 repo\\n存過答案？'),
    'd3': ('ask', 2, 1, '答案指向的節點\\n這次還在？'),
    'skip': ('out', 3.12, 1, '略過這個名稱，\\n不建立新節點'),
    's1': ('step', 3, 0, '1. 記成問題，\\n不默默丟掉'),
    's2': ('step', 4, 0, '2. 附排序過的候選，\\n與「新節點」「忽略」'),
    's3': ('step', 4, 1, '3. 在審查頁面或對話\\n裡一次問完'),
    's4': ('step', 4, 2, '4. 以確定性的\\n規則解析答案'),
    'd4': ('ask', 3, 2, '看得懂且指向\\n存在的節點？'),
    'keep': ('out', 3, 3, '保留問題，不猜'),
    's5': ('step', 2, 2, '5. 套用，並以 repo\\n為單位存下答案'),
    's6': ('step', 1, 2, '6. 報告標出這條邊\\n靠人的回答'),
}, [('start','d1'),('d1','m','能'),('d1','d2','不能'),('d2','d3','有'),('d3','m','在'),('d3','skip','不在'),('d2','s1','沒有'),
    ('s1','s2'),('s2','s3'),('s3','s4'),('s4','d4'),('d4','keep','否'),('d4','s5','是'),('s5','s6'),('s6','m')])

# ④ 基於事實的解釋
chart('p_flow4', {
    'start': ('term', 0, 0, '這次分析\\n收集到的結果'),
    's1': ('step', 1, 0, '1. 建一份統一模型\\n並存檔'),
    'v': ('step', 1, 1.1, '1. 圖、數字、問答、匯出\\n都從模型產生'),
    's2': ('step', 2, 0, '2. 記下收集了哪些來源；\\n狀態三值，預設「未知」'),
    's3': ('step', 3, 0, '3. 報告內容分成\\n事實與詮釋'),
    'f': ('step', 3, 1.1, '4. 事實由程式從模型產生；\\n未知寫「未量測」與原因，\\n不寫 0'),
    'i': ('step', 4, 1.1, '5. 詮釋：LLM 讀同一份\\n事實，寫角色、風險、建議'),
    'd2': ('ask', 4, 2.2, 'LLM 也寫了\\n事實段落？'),
    'drop': ('step', 4, 3.2, '丟掉 LLM 的版本'),
    's6': ('step', 3, 2.2, '6. 拼成報告，標出程式\\n產生的段落；失敗要記錄'),
    'end': ('term', 2, 2.2, '各產出物\\n說同一件事'),
}, [('start','s1'),('s1','s2'),('s1','v'),('s2','s3'),('s3','f'),('s3','i'),('f','s6'),('i','d2'),('d2','drop','是'),('d2','s6','否'),
    ('drop','s6'),('s6','end'),('v','end')])
