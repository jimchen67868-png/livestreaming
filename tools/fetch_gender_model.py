"""Fetches the MIT-licensed face gender classifier (128x128 -> 2 classes) from shubham0204/Age-Gender_Estimation_TF-Android."""
import json, os, urllib.request
import numpy as np
import tensorflow as tf

REPO = "shubham0204/Age-Gender_Estimation_TF-Android"
OUT = "app/src/main/assets/gender.tflite"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "fetch-script"})
    return urllib.request.urlopen(req, timeout=120).read()

tree = json.loads(fetch(f"https://api.github.com/repos/{REPO}/git/trees/master?recursive=1"))
paths = [t["path"] for t in tree["tree"] if t["path"].lower().endswith(".tflite")]
print("tflite files in repo:", paths)
cands = [p for p in paths if "gender" in p.lower()]
cands.sort(key=lambda p: ("lite" in p.lower().replace("tflite", ""), p.lower().endswith("_q.tflite"), len(p)))
for p in cands:
    print("trying", p)
    data = fetch(f"https://raw.githubusercontent.com/{REPO}/master/{p}")
    it = tf.lite.Interpreter(model_content=data)
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    print("  input", i["shape"], i["dtype"].__name__, "output", o["shape"])
    if list(i["shape"]) == [1, 128, 128, 3] and i["dtype"] == np.float32 and list(o["shape"]) == [1, 2]:
        it.set_tensor(i["index"], np.full(i["shape"], 0.5, dtype=np.float32))
        it.invoke()
        print("  sample output:", it.get_tensor(o["index"])[0])
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        open(OUT, "wb").write(data)
        print("wrote", OUT, len(data), "bytes")
        raise SystemExit(0)
raise SystemExit("No usable gender model found (see the list above).")
