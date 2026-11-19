-- 옷 사진 벡터로 같은 옷 찾기 (mcp-clothes-state 이슈 #15)
-- clothes_inspect가 옷마다 준 이미지 벡터(Marqo FashionSigLIP, 768개)를 둡니다. 사진은 여전히 저장하지 않습니다.
-- 같은 옷을 다시 보면 지금까지 본 벡터의 평균으로 바꿉니다(image_count = 평균에 들어간 사진 수, 최대 closet.image.max-count).
-- 모델이 바뀌면 벡터끼리 비교할 수 없어서 image_model이 같은 옷끼리만 비교합니다.
ALTER TABLE garments
  ADD COLUMN image_embedding public.vector(768),
  ADD COLUMN image_model     varchar(40),
  ADD COLUMN image_count     integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT garments_image_model_check CHECK ((image_embedding IS NULL) = (image_model IS NULL));
