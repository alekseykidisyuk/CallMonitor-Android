#!/usr/bin/env python3
"""Generate one PHP updater; embeds source files and old/new SHA gates only."""
import argparse, base64, hashlib, pathlib
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
items=[]
for rel in ['config/capture_profiles.php','app/ogg.php','app/api.php','app/core.php']:
    src=root/'updates/capture_profiles.php' if rel.startswith('config/') else root/'private'/rel
    data=src.read_bytes(); previous=root/'tests/baseline_v010'/pathlib.Path(rel).name
    old="'"+hashlib.sha256(previous.read_bytes()).hexdigest()+"'" if rel.startswith('app/') else 'null'
    items.append("'"+rel+"'=>['before'=>"+old+",'after'=>'"+hashlib.sha256(data).hexdigest()+"','content_b64'=>'"+base64.b64encode(data).decode()+"']")
manifest='[\n'+',\n'.join(items)+'\n]'
text=(root/'updates/updater_template.php').read_text().replace('/* PAYLOAD_MANIFEST */ []',manifest)
out=pathlib.Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(text)
print('Built updater:',out.name)
