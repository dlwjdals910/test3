db_images에 사진들 따로 넣기(150개 정도면 돌아감)

venv 설정 할 때 이부분 쓰고 venv 실행
Set-ExecutionPolicy RemoteSigned -Scope Process

venv 실행
.\venv\Scripts\Activate.ps1

가상환경에 들어가면 다음 코드 순차적으로 실행 1번은 사진 분석(눈, 코, 입이 분석이 안되면 db에 포함하지 않음) 2번은 프로토타입(웹캠 기반으로 실행)
python .\01_build_feature_db.py
python .\02_run_realtime_webcam.py
