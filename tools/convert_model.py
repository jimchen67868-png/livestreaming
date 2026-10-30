"""Downloads GantMan's open-source NSFW MobileNetV2 (MIT license) and converts it to TFLite."""
import urllib.request
import tensorflow as tf
import tensorflow_hub as hub

URL = "https://s3.amazonaws.com/ir_public/nsfwjscdn/nsfw_mobilenet2.224x224.h5"
urllib.request.urlretrieve(URL, "nsfw.h5")

model = tf.keras.models.load_model("nsfw.h5", custom_objects={"KerasLayer": hub.KerasLayer}, compile=False)
print("input:", model.input_shape, "output:", model.output_shape)  # expect (None,224,224,3) -> (None,5)

conv = tf.lite.TFLiteConverter.from_keras_model(model)
conv.optimizations = [tf.lite.Optimize.DEFAULT]
data = conv.convert()
with open("app/src/main/assets/nsfw.tflite", "wb") as f:
    f.write(data)
print("wrote app/src/main/assets/nsfw.tflite", len(data), "bytes")
