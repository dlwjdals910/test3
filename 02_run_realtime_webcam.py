import os
import cv2
import numpy as np
from scipy.spatial.distance import cosine
import time
from PIL import ImageFont, ImageDraw, Image

# --- TensorFlow Lite Interpreter 로드 ---
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
try:
    from tflite_runtime.interpreter import Interpreter
except ImportError:
    from tensorflow.lite.python.interpreter import Interpreter

# --- 1. 설정 및 상수 정의 ---

# --- 파일 경로 ---
DB_PATH = "feature_db.npz"
DB_IMAGE_DIR = "db_images"
FEATURE_MODEL_PATH = "models/feature_extractor.tflite"
POSE_MODEL_PATH = "models/pose_model.tflite"
FONT_PATH = "C:/Windows/Fonts/malgun.ttf"

# --- 화면 표시 크기 설정 ---
DISPLAY_WIDTH = 1280
DISPLAY_HEIGHT = 720

# --- 폰트 로드 ---
try:
    font_large = ImageFont.truetype(FONT_PATH, 30)
    font_small = ImageFont.truetype(FONT_PATH, 20)
except IOError:
    print(f"!!! 경고: '{FONT_PATH}' 폰트를 찾을 수 없습니다. 기본 폰트를 사용합니다.")
    font_large = ImageFont.load_default()
    font_small = ImageFont.load_default()

# --- AI 모델 파라미터 ---
TOP_K = 5  # DB에서 배경이 유사한 상위 5개를 바로 사용
CONFIDENCE_THRESHOLD = 0.3

# --- UI/UX 설정 ---
THUMBNAIL_WIDTH = 320
THUMBNAIL_HEIGHT = 240
GUIDE_THUMBNAIL_WIDTH = 160
GUIDE_THUMBNAIL_HEIGHT = 120

# --- 프로그램 상태 관리 ---
MODE_SEARCHING = 0
MODE_GUIDING = 1

# --- MoveNet 모델 관련 상수 ---
KEYPOINT_DICT = {
    'nose': 0, 'left_eye': 1, 'right_eye': 2, 'left_ear': 3, 'right_ear': 4,
    'left_shoulder': 5, 'right_shoulder': 6, 'left_elbow': 7, 'right_elbow': 8,
    'left_wrist': 9, 'right_wrist': 10, 'left_hip': 11, 'right_hip': 12,
    'left_knee': 13, 'right_knee': 14, 'left_ankle': 15, 'right_ankle': 16
}
REV_KEYPOINT_DICT = {v: k for k, v in KEYPOINT_DICT.items()}
CONNECTIONS = [(5, 6), (5, 7), (6, 8), (7, 9), (8, 10), (5, 11), (6, 12), (11, 12), (11, 13), (12, 14), (13, 15), (14, 16)]
KEYPOINT_COLORS = {
    'nose': (255, 255, 0), 'left_eye': (0, 0, 255), 'right_eye': (0, 0, 255),
    'left_ear': (0, 255, 0), 'right_ear': (0, 255, 0), 'left_shoulder': (255, 0, 0),
    'right_shoulder': (255, 0, 0), 'left_elbow': (255, 0, 255), 'right_elbow': (255, 0, 255),
    'left_wrist': (0, 255, 255), 'right_wrist': (0, 255, 255), 'left_hip': (255, 128, 0),
    'right_hip': (255, 128, 0), 'left_knee': (128, 0, 255), 'right_knee': (128, 0, 255),
    'left_ankle': (255, 255, 255), 'right_ankle': (255, 255, 255),
}
CONNECTION_COLOR = (192, 192, 192)

# --- 2. AI 모델 추론 및 처리 함수 ---

# --- [삭제] is_pose_valid 함수는 더 이상 03파일에서 필요 없음 ---

def get_pose_center_and_size(keypoints, img_shape):
    h, w = img_shape[:2]
    valid_keypoints = [kp for kp in keypoints if kp[2] > CONFIDENCE_THRESHOLD]
    print(f"\rDetected valid keypoints: {len(valid_keypoints)}   ", end="")
    if len(valid_keypoints) < 2:
        return None, None
    points_x = [kp[1] * w for kp in valid_keypoints]
    points_y = [kp[0] * h for kp in valid_keypoints]
    min_x, max_x = min(points_x), max(points_x)
    min_y, max_y = min(points_y), max(points_y)
    center_x = (min_x + max_x) / 2
    center_y = (min_y + max_y) / 2
    size = (max_x - min_x) * (max_y - min_y)
    return (center_x, center_y), size

def run_inference_on_frame(interpreter, image_bgr, target_size):
    img_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    img_resized = cv2.resize(img_rgb, target_size)
    input_data = np.expand_dims(img_resized, axis=0)
    input_details = interpreter.get_input_details()[0]
    if input_details['dtype'] == np.float32:
        input_data = input_data.astype(np.float32) / 255.0
    interpreter.set_tensor(input_details['index'], input_data)
    interpreter.invoke()
    output_details = interpreter.get_output_details()[0]
    return np.squeeze(interpreter.get_tensor(output_details['index']))

def find_similar_images(features, db_data, limit):
    distances = [cosine(features, db_feature) for db_feature in db_data['features']]
    sorted_indices = np.argsort(distances)
    return [{'path': db_data['filepaths'][i], 'distance': distances[i]} for i in sorted_indices[:limit]]

def generate_pose_feedback(target_kps, live_kps):
    HEAD_INDICES, SHOULDER_INDICES = [0, 1, 2, 3, 4], [5, 6]
    if not all(target_kps[i][2] > CONFIDENCE_THRESHOLD and live_kps[i][2] > CONFIDENCE_THRESHOLD for i in HEAD_INDICES + SHOULDER_INDICES):
        return "자세 인식이 어렵습니다."
    target_head_y = np.mean([target_kps[i][0] for i in HEAD_INDICES])
    live_head_y = np.mean([live_kps[i][0] for i in HEAD_INDICES])
    target_shoulder_x = (target_kps[SHOULDER_INDICES[0]][1] + target_kps[SHOULDER_INDICES[1]][1]) / 2
    live_shoulder_x = (live_kps[SHOULDER_INDICES[0]][1] + live_kps[SHOULDER_INDICES[1]][1]) / 2
    dy, dx = target_head_y - live_head_y, target_shoulder_x - live_shoulder_x
    if abs(dy) > 0.05: return "고개를 아래로 숙이세요." if dy > 0 else "고개를 위로 올리세요."
    if abs(dx) > 0.05: return "몸을 좌측으로 돌리세요." if dx > 0 else "몸을 우측으로 돌리세요."
    return None

def generate_position_feedback(target_center, target_size, live_center, live_size):
    if any(v is None for v in [target_center, target_size, live_center, live_size]):
        return "위치를 찾을 수 없습니다.\n(팁: 상반신이 더 잘 보이도록 조금 뒤로 움직여 보세요)"
    dx = target_center[0] - live_center[0]
    d_size_ratio = target_size / live_size if live_size > 0 else float('inf')
    if d_size_ratio > 1.3: return "뒤로 이동해주세요."
    if d_size_ratio < 0.7: return "앞으로 이동해주세요."
    if abs(dx) > 50: return "오른쪽으로 이동해주세요." if dx > 0 else "왼쪽으로 이동해주세요."
    return None

# --- 3. 시각화 및 UI 관련 함수 ---
def resize_to_fit(image, max_width, max_height):
    h, w = image.shape[:2]
    scale = min(max_width / w, max_height / h)
    new_w, new_h = int(w * scale), int(h * scale)
    return cv2.resize(image, (new_w, new_h))

def draw_text_with_outline(frame, texts_to_draw):
    img_pil = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img_pil)
    outline_color = (0, 0, 0)
    for text, pos, font, fill_color in texts_to_draw:
        draw.multiline_text((pos[0]-2, pos[1]), text, font=font, fill=outline_color)
        draw.multiline_text((pos[0]+2, pos[1]), text, font=font, fill=outline_color)
        draw.multiline_text((pos[0], pos[1]-2), text, font=font, fill=outline_color)
        draw.multiline_text((pos[0], pos[1]+2), text, font=font, fill=outline_color)
        draw.multiline_text(pos, text, font=font, fill=fill_color)
    return cv2.cvtColor(np.array(img_pil), cv2.COLOR_RGB2BGR)

def draw_pose_on_image(image, keypoints, color_override=None):
    h, w, _ = image.shape
    for i, kp in enumerate(keypoints):
        if kp[2] > CONFIDENCE_THRESHOLD:
            y, x, conf = kp
            center = (int(x * w), int(y * h))
            if color_override: color = color_override
            else:
                keypoint_name = REV_KEYPOINT_DICT.get(i)
                color = KEYPOINT_COLORS.get(keypoint_name, (255, 255, 255))
            cv2.circle(image, center, 5, color, -1)
    for start_idx, end_idx in CONNECTIONS:
        start_kp, end_kp = keypoints[start_idx], keypoints[end_idx]
        if start_kp[2] > CONFIDENCE_THRESHOLD and end_kp[2] > CONFIDENCE_THRESHOLD:
            start_pos = (int(start_kp[1] * w), int(start_kp[0] * h))
            end_pos = (int(end_kp[1] * w), int(end_kp[0] * h))
            cv2.line(image, start_pos, end_pos, CONNECTION_COLOR, 2)
    return image

def close_all_thumbnail_windows():
    for i in range(1, TOP_K + 1): # TOP_K 개수만큼만 닫으면 됨
        try: cv2.destroyWindow(str(i))
        except: pass

# --- 4. 메인 실행 로직 ---
def main():
    current_mode = MODE_SEARCHING
    similar_images, selected_guide_path, selected_guide_frame = None, None, None
    guide_thumbnail_frame, target_kps, target_pos_center, target_pos_size = None, None, None, None

    print(">>> AI 모델 및 DB 로드 중...")
    try:
        feature_interpreter = Interpreter(model_path=FEATURE_MODEL_PATH); feature_interpreter.allocate_tensors()
        pose_interpreter = Interpreter(model_path=POSE_MODEL_PATH); pose_interpreter.allocate_tensors()
        if not os.path.exists(DB_PATH):
            np.savez(DB_PATH, features=np.array([]), filepaths=np.array([]))
        db_data = np.load(DB_PATH, allow_pickle=True)
    except Exception as e:
        print(f"!!! 초기화 실패: {e}"); return
    print(">>> 초기화 완료.")

    if not os.path.isdir(DB_IMAGE_DIR): os.makedirs(DB_IMAGE_DIR)

    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("!!! 웹캠을 열 수 없습니다."); return
        
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280); cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    actual_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    actual_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f">>> 웹캠 해상도 요청: 1280x720, 실제 적용된 해상도: {actual_width}x{actual_height}")
        
    pose_input_details = pose_interpreter.get_input_details()[0]
    pose_input_size = (pose_input_details['shape'][2], pose_input_details['shape'][1])

    while True:
        ret, frame = cap.read()
        if not ret: break
        frame = cv2.flip(frame, 1)
        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'): break
        
        texts_to_draw = []

        if key == ord('k'):
            # k키 DB 추가 로직 (생략 없음)
            print("\n--- [DB 추가 요청] 현재 프레임을 DB에 추가합니다. ---")
            timestamp = int(time.time())
            filename = f"capture_{timestamp}.jpg"
            filepath = os.path.join(DB_IMAGE_DIR, filename)
            cv2.imwrite(filepath, frame)
            print(f" - 이미지 저장 완료: {filepath}")
            print(" - 특징 추출 중...")
            f_input_details = feature_interpreter.get_input_details()[0]
            f_input_size = (f_input_details['shape'][2], f_input_details['shape'][1])
            features = run_inference_on_frame(feature_interpreter, frame, f_input_size)
            print(" - 특징 추출 완료.")
            current_features = list(db_data.get('features', []))
            current_filepaths = list(db_data.get('filepaths', []))
            current_features.append(features)
            current_filepaths.append(filepath)
            np.savez(DB_PATH, features=np.array(current_features), filepaths=np.array(current_filepaths))
            db_data = np.load(DB_PATH, allow_pickle=True)
            print(f" - 데이터베이스 업데이트 완료! (총 {len(db_data['filepaths'])}개)")
            texts_to_draw.append(("DB에 현재 이미지 추가 완료!", (50, 200), font_large, (0, 255, 255)))

        if current_mode == MODE_SEARCHING:
            if similar_images is None:
                texts_to_draw.append(("모드: 배경 검색", (20, 30), font_large, (255, 255, 255)))
                texts_to_draw.append(("'s': 배경 검색", (20, 70), font_large, (255, 255, 255)))
                if key == ord('s'):
                    if len(db_data.get('features', [])) == 0:
                        texts_to_draw.append(("DB가 비어있습니다. 'k' 또는 '01_build...'을 실행하세요.", (50, 200), font_large, (0, 0, 255)))
                    else:
                        print("\n--- [단계 1] 유사 이미지 검색 (DB는 이미 검증됨) ---")
                        feature_input_details = feature_interpreter.get_input_details()[0]
                        feature_input_size = (feature_input_details['shape'][2], feature_input_details['shape'][1])
                        features = run_inference_on_frame(feature_interpreter, frame, feature_input_size)
                        
                        # --- [핵심 수정] 자세 유효성 검사 로직이 완전히 사라지고, 코드가 매우 단순해짐 ---
                        # DB에 있는 데이터는 모두 유효하므로, 상위 TOP_K개 결과를 바로 사용합니다.
                        similar_images = find_similar_images(features, db_data, TOP_K)
                        
                        if not similar_images:
                             texts_to_draw.append(("유사한 배경의 가이드를 찾지 못했습니다.", (50, 200), font_large, (255, 0, 0)))
                        else:
                            print(f"--- {len(similar_images)}개의 추천 결과를 찾았습니다. ---")
                            for i, img_info in enumerate(similar_images):
                                thumb = cv2.resize(cv2.imread(img_info['path']), (THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
                                cv2.imshow(str(i + 1), thumb); cv2.moveWindow(str(i + 1), i * (THUMBNAIL_WIDTH + 10), 50)
            
            elif selected_guide_path is None:
                texts_to_draw.append(("모드: 가이드 선택", (20, 30), font_large, (255, 255, 255)))
                texts_to_draw.append(("원하는 사진의 번호(1~5)를 누르세요", (20, 70), font_large, (255, 255, 255)))
                if ord('1') <= key <= ord('5'):
                    choice_idx = key - ord('1')
                    if similar_images and choice_idx < len(similar_images):
                        selected_guide_path = similar_images[choice_idx]['path']
                        close_all_thumbnail_windows()
                        selected_guide_frame = cv2.imread(selected_guide_path)
                        kps = run_inference_on_frame(pose_interpreter, selected_guide_frame, pose_input_size)
                        guide_with_pose = selected_guide_frame.copy()
                        draw_pose_on_image(guide_with_pose, kps)
                        display_guide_frame = resize_to_fit(guide_with_pose, DISPLAY_WIDTH, DISPLAY_HEIGHT)
                        cv2.imshow("Selected Guide (c:Confirm, r:Cancel)", display_guide_frame)
                elif key == ord('r'):
                    close_all_thumbnail_windows(); similar_images = None

            else:
                texts_to_draw.append(("모드: 선택 확인", (20, 30), font_large, (255, 255, 255)))
                texts_to_draw.append(("'c' 키로 가이드 시작", (20, 70), font_large, (255, 255, 255)))
                if key == ord('c'):
                    cv2.destroyWindow("Selected Guide (c:Confirm, r:Cancel)")
                    guide_image = selected_guide_frame
                    target_kps = run_inference_on_frame(pose_interpreter, guide_image, pose_input_size)
                    target_pos_center, target_pos_size = get_pose_center_and_size(target_kps, guide_image.shape)
                    guide_image_with_pose = guide_image.copy()
                    draw_pose_on_image(guide_image_with_pose, target_kps)
                    guide_thumbnail_frame = cv2.resize(guide_image_with_pose, (GUIDE_THUMBNAIL_WIDTH, GUIDE_THUMBNAIL_HEIGHT))
                    current_mode = MODE_GUIDING
                elif key == ord('r'): 
                    cv2.destroyWindow("Selected Guide (c:Confirm, r:Cancel)"); selected_guide_path, selected_guide_frame = None, None

        elif current_mode == MODE_GUIDING:
            live_kps = run_inference_on_frame(pose_interpreter, frame, pose_input_size)
            live_pos_center, live_pos_size = get_pose_center_and_size(live_kps, frame.shape)
            pose_feedback = generate_pose_feedback(target_kps, live_kps)
            position_feedback = generate_position_feedback(target_pos_center, target_pos_size, live_pos_center, live_pos_size)
            frame = draw_pose_on_image(frame, live_kps)
            overlay = np.zeros_like(frame, dtype=np.uint8)
            overlay = draw_pose_on_image(overlay, target_kps, color_override=(255, 0, 0))
            frame = cv2.addWeighted(frame, 1, overlay, 0.5, 0)
            
            texts_to_draw.append(("모드: 실시간 가이드", (20, 30), font_large, (0, 255, 255)))
            texts_to_draw.append((f"자세: {pose_feedback or '좋음'}", (20, 70), font_large, (0, 255, 0)))
            texts_to_draw.append((f"위치: {position_feedback or '좋음'}", (20, 110), font_large, (0, 255, 0)))
            texts_to_draw.append(("'r' 키를 눌러 초기화", (20, 680), font_large, (255, 255, 255)))
            
            if guide_thumbnail_frame is not None:
                frame_h, frame_w, _ = frame.shape
                thumb_h, thumb_w, _ = guide_thumbnail_frame.shape
                thumb_pos_x = frame_w - thumb_w - 20; thumb_pos_y = 20
                frame[thumb_pos_y:thumb_pos_y+thumb_h, thumb_pos_x:thumb_pos_x+thumb_w] = guide_thumbnail_frame
                texts_to_draw.append(("분석된 가이드", (thumb_pos_x, thumb_pos_y + thumb_h + 5), font_small, (255, 255, 0)))
            
            if key == ord('r'):
                print("\n>>> 가이드 초기화. 검색 모드로 돌아갑니다.")
                current_mode = MODE_SEARCHING
                similar_images, selected_guide_path, selected_guide_frame = None, None, None
                guide_thumbnail_frame, target_kps, target_pos_center, target_pos_size = None, None, None, None
        
        if texts_to_draw:
            frame = draw_text_with_outline(frame, texts_to_draw)

        h, w, _ = frame.shape
        scale = min(DISPLAY_WIDTH / w, DISPLAY_HEIGHT / h)
        new_w, new_h = int(w * scale), int(h * scale)
        display_frame = cv2.resize(frame, (new_w, new_h))
        final_frame = np.zeros((DISPLAY_HEIGHT, DISPLAY_WIDTH, 3), dtype=np.uint8)
        y_offset = (DISPLAY_HEIGHT - new_h) // 2
        x_offset = (DISPLAY_WIDTH - new_w) // 2
        final_frame[y_offset:y_offset+new_h, x_offset:x_offset+new_w] = display_frame

        cv2.imshow("AIGuideCam (Press 'q' to quit)", final_frame)

    cap.release()
    cv2.destroyAllWindows()

if __name__ == '__main__':
    main()