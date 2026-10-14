---
license: apache-2.0
---
ONNX conversion of Google's [YAMNet model](https://www.kaggle.com/models/google/yamnet/tensorFlow2/yamnet/1)

```
pip install "tensorflow>=2.15,<2.17" "numpy<2.0" "tf2onnx>=1.16"
python -m tf2onnx.convert --saved-model path/to/model --output yamnet.onnx
```