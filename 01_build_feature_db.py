import os
import numpy as np
import cv2

# --- TensorFlow Lite Interpreter 로드 ---
# TensorFlow의 로그 메시지 수준을 조정하여 불필요한 경고를 숨깁니다.
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
try:
    # tflite-runtime이 설치된 경우, 경량화된 인터프리터를 사용합니다.
    from tflite_runtime.interpreter import Interpreter
except ImportError:
    # TensorFlow 전체가 설치된 경우, 표준 인터프리터를 사용합니다.
    from tensorflow.lite.python.interpreter import Interpreter

# --- 1. 설정 및 상수 정의 ---

# --- 파일 경로 ---
FEATURE_MODEL_PATH = "models/feature_extractor.tflite"
POSE_MODEL_PATH = "models/pose_model.tflite"  # 자세 인식을 위한 모델 경로 추가
DB_IMAGE_DIR = "db_images"
OUTPUT_DB_PATH = "feature_db.npz"

# --- 자세 인식을 위한 상수 (03번 파일과 동일하게 유지) ---
KEYPOINT_DICT = {
    'nose': 0, 'left_eye': 1, 'right_eye': 2, 'left_ear': 3, 'right_ear': 4,
    'left_shoulder': 5, 'right_shoulder': 6, 'left_elbow': 7, 'right_elbow': 8,
    'left_wrist': 9, 'right_wrist': 10, 'left_hip': 11, 'right_hip': 12,
    'left_knee': 13, 'right_knee': 14, 'left_ankle': 15, 'right_ankle': 16
}
CONFIDENCE_THRESHOLD = 0.3 # 자세 점(keypoint)이 유효하다고 판단하는 최소 신뢰도

# --- 2. 핵심 함수 정의 ---

def is_pose_valid(keypoints):
    """
    주요 얼굴 부위(코, 양눈, 양귀)가 모두 인식되었는지 확인하는 함수.
    이 함수를 통과해야만 '유효한 가이드 사진'으로 인정됩니다.
    """
    required_indices = [
        KEYPOINT_DICT['nose'],
        KEYPOINT_DICT['left_eye'],
        KEYPOINT_DICT['right_eye'],
        KEYPOINT_DICT['left_ear'],
        KEYPOINT_DICT['right_ear']
    ]
    # all() 함수는 모든 조건(신뢰도가 임계값보다 높은지)이 True일 때만 True를 반환합니다.
    return all(keypoints[i][2] > CONFIDENCE_THRESHOLD for i in required_indices)

def run_inference(interpreter, image_bgr, target_size):
    """
    주어진 이미지로 AI 모델 추론을 실행하고 결과를 반환하는 범용 함수.
    특징 추출 및 자세 인식에 모두 사용됩니다.
    """
    img_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    img_resized = cv2.resize(img_rgb, target_size)
    input_data = np.expand_dims(img_resized, axis=0)
    
    input_details = interpreter.get_input_details()[0]
    # 모델이 요구하는 입력 데이터 타입이 소수점(FLOAT32) 형태이면,
    # 데이터 타입을 변환하고 픽셀 값을 0~1로 정규화합니다.
    if input_details['dtype'] == np.float32:
        input_data = input_data.astype(np.float32) / 255.0
        
    interpreter.set_tensor(input_details['index'], input_data)
    interpreter.invoke()
    
    output_details = interpreter.get_output_details()[0]
    return np.squeeze(interpreter.get_tensor(output_details['index']))

# --- 3. 메인 실행 로직 ---
def main():
    """
    이 스크립트의 역할:
    1. db_images 폴더의 모든 이미지 파일명을 0001.jpg, 0002.jpg 등으로 순서대로 변경합니다.
    2. 모든 이미지에 대해 '자세 인식'을 먼저 수행합니다.
    3. 얼굴(눈,코,귀)이 명확하게 인식되는 '유효한' 이미지만 선별합니다.
    4. 선별된 유효한 이미지들에 대해서만 '배경 특징'을 추출하여 최종 DB 파일(feature_db.npz)로 저장합니다.
    """
    print(">>> [자세 검증 포함 DB 재구축 모드]를 시작합니다.")
    
    if not os.path.isdir(DB_IMAGE_DIR):
        print(f"!!! 오류: '{DB_IMAGE_DIR}' 폴더를 찾을 수 없습니다."); return

    # --- 단계 1: 파일명 변경 작업 ---
    print("\n--- 단계 1: 이미지 파일명 일괄 변경 ---")
    current_files = sorted([f for f in os.listdir(DB_IMAGE_DIR) if f.lower().endswith(('.jpg', '.png', '.jpeg'))])
    if not current_files:
        print("!!! db_images 폴더에 분석할 이미지가 없습니다."); return
    
    renamed_filepaths = []
    for i, filename in enumerate(current_files):
        old_filepath = os.path.join(DB_IMAGE_DIR, filename)
        extension = os.path.splitext(filename)[1]
        new_filename = f"{i+1:04d}{extension}" # 예: 0001.jpg, 0012.png
        new_filepath = os.path.join(DB_IMAGE_DIR, new_filename)
        # 자기 자신으로 이름을 바꾸는 경우를 제외하고 이름 변경 실행
        if old_filepath != new_filepath:
            os.rename(old_filepath, new_filepath)
        renamed_filepaths.append(new_filepath)
    print(f" - 총 {len(renamed_filepaths)}개 파일의 이름을 순서대로 정리했습니다.")

    # --- 단계 2: AI 모델 로드 ---
    print("\n--- 단계 2: AI 모델 로드 ---")
    try:
        feature_interpreter = Interpreter(model_path=FEATURE_MODEL_PATH); feature_interpreter.allocate_tensors()
        pose_interpreter = Interpreter(model_path=POSE_MODEL_PATH); pose_interpreter.allocate_tensors()
    except Exception as e:
        print(f"!!! AI 모델 로드 실패: {e}"); return
    
    # 각 모델이 요구하는 입력 이미지 크기를 가져옴
    feature_input_details = feature_interpreter.get_input_details()[0]
    feature_input_size = (feature_input_details['shape'][2], feature_input_details['shape'][1])
    pose_input_details = pose_interpreter.get_input_details()[0]
    pose_input_size = (pose_input_details['shape'][2], pose_input_details['shape'][1])
    print(" - 배경 특징 추출 모델 및 자세 인식 모델 로드 완료.")

    # --- 단계 3: 유효성 검사 및 특징 추출 ---
    print("\n--- 단계 3: 자세 유효성 검사 및 특징 추출 시작 ---")
    all_features = []
    all_filepaths = []
    
    # 이름이 변경된 모든 파일을 하나씩 처리
    for filepath in renamed_filepaths:
        image = cv2.imread(filepath)
        if image is None:
            print(f"!!! 경고: '{filepath}' 파일을 읽을 수 없어 건너뜁니다.")
            continue
        
        # (1) 자세 인식 먼저 수행
        keypoints = run_inference(pose_interpreter, image, pose_input_size)
        
        # (2) 자세 유효성 검사 (눈, 코, 귀가 모두 인식되었는지)
        if is_pose_valid(keypoints):
            print(f"  [O] 유효한 자세 확인: {os.path.basename(filepath)} -> 특징 추출 진행")
            # (3) 유효한 사진에 대해서만 배경 특징 추출 수행
            features = run_inference(feature_interpreter, image, feature_input_size)
            all_features.append(features)
            all_filepaths.append(filepath)
        else:
            print(f"  [X] 유효하지 않은 자세: {os.path.basename(filepath)} -> DB에서 제외합니다.")

    # --- 단계 4: 최종 결과 저장 ---
    if all_features:
        # 유효한 사진들의 특징 벡터와 파일 경로를 .npz 파일로 압축 저장
        np.savez(OUTPUT_DB_PATH, features=np.array(all_features), filepaths=np.array(all_filepaths))
        print(f"\n>>> DB 재구축 완료! '{OUTPUT_DB_PATH}' 파일이 새로 생성되었습니다.")
        print(f" - 총 {len(renamed_filepaths)}개의 이미지 중 {len(all_filepaths)}개가 유효하여 DB에 최종 저장되었습니다.")
    else:
        print("\n>>> 유효한 자세를 가진 이미지가 하나도 없어 DB 파일을 생성하지 않았습니다.")
        # 기존 DB 파일이 있다면 삭제하여 혼동을 방지
        if os.path.exists(OUTPUT_DB_PATH):
            os.remove(OUTPUT_DB_PATH)

if __name__ == '__main__':
    main()