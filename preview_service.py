
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from jinja2 import Environment, BaseLoader
import pickle
import time
import re

app = FastAPI()

env = Environment(loader=BaseLoader(), autoescape=True)

ALLOWED = {"customer.name", "invoice.amount_eur"}

CACHE: dict[str, tuple[float, bytes]] = {}

class PreviewReq(BaseModel):
    preview_id: str
    template: str
    customer_name: str
    amount_eur: float

def cache_set(key: str, obj: dict, ttl: int = 60) -> None:
  
    payload = pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)
    CACHE[key] = (time.time() + ttl, payload)

def cache_get(key: str) -> dict | None:
    item = CACHE.get(key)
    if not item:
        return None
    exp, payload = item
    if time.time() > exp:
        CACHE.pop(key, None)
        return None

    return pickle.loads(payload)

@app.post("/preview")
def preview(req: PreviewReq):

    ids = set(re.findall(r"\{\{\s*([a-zA-Z_][\w\.]*)\s*\}\}", req.template))
    if not ids.issubset(ALLOWED):
        raise HTTPException(status_code=400, detail="unsupported placeholders")

    ctx = {
        "customer": {"name": req.customer_name},
        "invoice": {"amount_eur": req.amount_eur},
    }

    try:
        html = env.from_string(req.template).render(**ctx)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"render error: {type(e).__name__}")

    cache_set(f"preview:{req.preview_id}", {"html": html})
    return {"html": html}

@app.get("/preview/{preview_id}")
def get_cached(preview_id: str):
    obj = cache_get(f"preview:{preview_id}")
    if not obj:
        raise HTTPException(status_code=404, detail="not found")
    return obj
