"""Smoke HTTP using the real startup/model, with no model or database mocks."""
import json
from pathlib import Path
import sys
import time
from uuid import uuid4
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from fastapi.testclient import TestClient
from main import app
from local_lora import GENERATION_DEADLINE


def main():
    start=time.perf_counter()
    records=[]
    with TestClient(app) as client:
        startup_ms=(time.perf_counter()-start)*1000
        health=client.get('/health')
        records.append({'path':'/health','status':health.status_code,'ia':health.headers.get('X-GuiaMed-IA'),'json':health.json()})
        assert health.headers.get('X-GuiaMed-IA')=='ready',records[-1]
        device='ia-smoke-'+uuid4().hex
        payloads=[('/alergias',{'nome_alergia':'metamizol'}),
                  ('/verificar',{'texto_ocr':'NO VAL GI NA 5OO MG VAL: 10/26'}),
                  ('/receita',{'texto_ocr':'OMEPRAZOL 20 MG A NOITE\nNO VAL GI NA 500 MG A CADA 8 HORAS\nPAR ACE TAM OL 750 MG A CADA 12 HORAS'}),
                  ('/verificar',{'texto_ocr':'NOVALGINA 500 MG + METFORMINA 850 MG'}),
                  ('/verificar',{'texto_ocr':('INFORMACOES LEGAIS USO ORAL '*100)+'NO VAL GI NA 500 MG VAL: 12/27'})]
        for path,extra in payloads:
            start=time.perf_counter()
            r=client.post(path,json={'device_id':device,'timestamp':int(time.time()),**extra})
            records.append({'path':path,'status':r.status_code,'ms':(time.perf_counter()-start)*1000,
                            'ia':r.headers.get('X-GuiaMed-IA'),'json':r.json()})
            assert r.status_code==200,records[-1]
        verification=records[2]['json']
        assert verification['nome']=='Dipirona Sódica' and verification['miligramas']=='500mg'
        assert verification['vencido'] and verification['alerta_alergia'] and verification['perigo']
        assert [m['dosagem'] for m in records[3]['json']['medicamentos']]==['20mg','500mg','750mg']
        assert records[4]['json']['nome'].startswith('Medicamento')
        runtime=app.state.local_lora
        start=time.monotonic()
        token=GENERATION_DEADLINE.set(start+.001)
        try:
            answer=runtime.suggest('NO VAL GI NA')
        finally:
            GENERATION_DEADLINE.reset(token)
        deadline_probe={'budget_ms':1,'elapsed_ms':(time.monotonic()-start)*1000,
                        'answer_discarded':answer is None,'state':runtime.last_result,
                        'lock_released':not runtime._generation_lock.locked()}
        assert deadline_probe['answer_discarded'] and deadline_probe['lock_released']
    out=ROOT/'evidencias'/'ia'/'catalog_real_active'/'http_smoke.json'
    out.write_text(json.dumps({'startup_ms':startup_ms,'records':records,'deadline_probe':deadline_probe,'passed':True},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'passed':True,'startup_ms':startup_ms,'ia':records[0]['ia']}))

if __name__=='__main__': main()
