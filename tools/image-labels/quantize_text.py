"""FashionSigLIP 글 모델(text_model.onnx, ~441MB)을 int8로 줄인다(~111MB).

closet_search의 imageQuery용. Java 쪽(ImageTextModel)과 Python fp32의 벡터 코사인은 0.98~0.995,
DeepFashion 600벌 벤치마크 정답률은 fp32 0.739, int8 0.733으로 거의 같다. 질문 하나 ~17ms(CPU).

사용: python quantize_text.py text_model.onnx text_model_int8.onnx
그 뒤 CLOSET_IMAGE_TEXT_MODEL_URI=file:/.../text_model_int8.onnx, CLOSET_IMAGE_TEXT_TOKENIZER_URI=file:/.../tokenizer.json
"""
import sys
from onnxruntime.quantization import QuantType, quantize_dynamic

quantize_dynamic(sys.argv[1], sys.argv[2], weight_type=QuantType.QInt8)
