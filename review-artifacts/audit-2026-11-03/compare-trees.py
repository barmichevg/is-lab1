"""Read GitHub tree metadata only; never import or execute repository contents."""
import json
from collections import Counter
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import quote

out=Path('review-artifacts/audit-2026-11-03')
projects=[
 ('maxbarsukov','maxbarsukov-itmo/information-systems-labs','145ee1e236309898ec40d654e5124e9bb91fd23e','','src/main/java/','src/test/java/','frontend/src/'),
 ('sultanowskii','sultanowskii/is-lab1','main','','app/src/main/java/','app/src/test/java/','app/src/main/resources/static/'),
 ('CodeAxeAttacks','CodeAxeAttacks/SystemApplicationSoftware-09.03.04-ITMO','main','3rd stage/ис/lab1/','src/main/java/','src/test/java/','frontend/src/'),
 ('Azat2202','Azat2202/itmo-labs','master','3 year/ИС/Лабораторная работа 1-3/','backend/src/main/java/','backend/src/test/java/','frontend/src/'),
]
def get(url):
 with urlopen(Request(url,headers={'User-Agent':'lab1-read-only-comparison','Accept':'application/vnd.github+json'}),timeout=40) as response:
  return json.load(response)
results=[]
for name,repo,ref,prefix,java,test,ui in projects:
 commit=get(f'https://api.github.com/repos/{repo}/commits/{ref}')['sha']
 url=f'https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1'
 tree=get(url)
 if tree.get('truncated'): raise RuntimeError(f'Truncated tree: {name}; cannot publish reliable counts')
 files=[dict(item,path=item['path'][len(prefix):]) for item in tree['tree'] if item['type']=='blob' and item['path'].startswith(prefix)]
 prod=[item for item in files if item['path'].startswith(java) and item['path'].endswith('.java')]
 tests=[item for item in files if item['path'].startswith(test) and item['path'].endswith('.java')]
 frontend=[item for item in files if item['path'].startswith(ui) and item['path'].rsplit('.',1)[-1] in ('html','js','jsx','ts','tsx','css','scss','sass')]
 info={'project':name,'repo':repo,'requested_ref':ref,'commit':commit,'tree_api':url,'subtree_prefix':prefix,
       'production_java_files':len(prod),'java_test_files':len(tests),'frontend_source_files':len(frontend),
       'production_java_bytes':sum(item.get('size',0) for item in prod),'frontend_source_bytes':sum(item.get('size',0) for item in frontend),
       'frontend_extensions':dict(Counter(item['path'].rsplit('.',1)[-1] for item in frontend)),
       'java_files':[item['path'] for item in prod],'test_files':[item['path'] for item in tests],'frontend_files':[item['path'] for item in frontend]}
 results.append(info)
 print(name,commit,'Java',len(prod),'Java tests',len(tests),'UI',len(frontend),flush=True)
(out/'comparison-trees.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
