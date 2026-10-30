"""Finds GantMan's open-source NSFW MobileNetV2 (MIT license), converts it to TFLite and checks it."""
import glob, json, os, shutil, urllib.request, zipfile
import numpy as np
import tensorflow as tf
import tensorflow_hub as hub

OUT = "app/src/main/assets/nsfw.tflite"
S3 = "https://s3.amazonaws.com/ir_public/nsfwjscdn/nsfw_mobilenet2.224x224.h5"


def get(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def candidates():
    """Yields paths (an .h5 file or a SavedModel folder), trying every known source."""
    try:
        get(S3, "nsfw.h5")
        yield "nsfw.h5"
    except Exception as e:
        print("S3 download failed:", e)
    for tag in ("1.2.0", "1.1.0"):
        try:
            req = urllib.request.Request(
                f"https://api.github.com/repos/GantMan/nsfw_model/releases/tags/{tag}",
                headers={"User-Agent": "convert-script", "Accept": "application/vnd.github+json"})
            rel = json.load(urllib.request.urlopen(req, timeout=60))
        except Exception as e:
            print(f"release {tag} lookup failed:", e)
            continue
        assets = rel.get("assets", [])
        print(f"release {tag} assets:", [a["name"] for a in assets])
        for a in assets:
            n = a["name"].lower()
            if "mobilenet" not in n or not (n.endswith(".zip") or n.endswith(".h5")):
                continue
            dest = f"dl_{tag}_{a['name']}"
            get(a["browser_download_url"], dest)
            if n.endswith(".h5"):
                yield dest
                continue
            folder = dest + "_x"
            with zipfile.ZipFile(dest) as z:
                z.extractall(folder)
            for h5 in glob.glob(folder + "/**/*.h5", recursive=True):
                yield h5
            for pb in glob.glob(folder + "/**/saved_model.pb", recursive=True):
                yield os.path.dirname(pb)


def convert(path):
    attempts = []
    if os.path.isdir(path):
        attempts.append(lambda: tf.lite.TFLiteConverter.from_saved_model(path))
    attempts.append(lambda: tf.lite.TFLiteConverter.from_keras_model(
        tf.keras.models.load_model(path, custom_objects={"KerasLayer": hub.KerasLayer}, compile=False)))
    for make in attempts:
        try:
            conv = make()
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
            return conv.convert()
        except Exception as e:
            print("  conversion attempt failed:", repr(e)[:300])
    return None


def check(data):
    it = tf.lite.Interpreter(model_content=data)
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    print("  input", i["shape"], "output", o["shape"])
    if list(o["shape"]) != [1, 5] or list(i["shape"])[1:3] != [224, 224]:
        return False
    it.set_tensor(i["index"], np.zeros(i["shape"], dtype=np.float32))
    it.invoke()
    print("  sample output (black image):", it.get_tensor(o["index"])[0])
    return True


for path in candidates():
    print("trying", path)
    data = convert(path)
    if data and check(data):
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, "wb") as f:
            f.write(data)
        print("wrote", OUT, len(data), "bytes")
        raise SystemExit(0)

raise SystemExit("No usable NSFW model found. See the lines above ('assets:' lists what the release contains).")
