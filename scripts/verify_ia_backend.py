"""Run backend regressions with UTF-8 evidence, without changing configuration."""
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[1]
env=dict(os.environ,PYTHONIOENCODING='utf-8')
command=[sys.executable,'-m','unittest','discover','-s',str(ROOT/'backend'),'-v']
result=subprocess.run(command,cwd=ROOT.parent,env=env,capture_output=True,text=True,encoding='utf-8')
out=ROOT/'evidencias'/'ia'/'backend_recovery_validation'
out.mkdir(exist_ok=True)
(out/'tests.log').write_text(result.stdout+result.stderr,encoding='utf-8')
(out/'result.json').write_text(json.dumps({'command':command,'exit_code':result.returncode,
    'utc':datetime.now(timezone.utc).isoformat(),'python':sys.version},indent=2),encoding='utf-8')
print((result.stdout+result.stderr)[-1200:])
sys.exit(result.returncode)
