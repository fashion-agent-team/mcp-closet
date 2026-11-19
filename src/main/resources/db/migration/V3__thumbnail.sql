-- 옷 부분 작은 사진 (2026-11-12 박창근 결정)
-- 사진 유사도가 애매할 때(closet_save maybeSameAs) agent가 GPT에게 이번 사진과 예전 사진을 비교하게 하려고,
-- 옷 부분만 자른 작은 JPEG(긴 변 256px 안팎, 얼굴 없음)를 둡니다. 우리 서버 DB에만 있고, 옷을 지우면 함께 지워집니다.
-- 같은 옷을 다시 보면 최근 사진으로 바꿉니다.
ALTER TABLE garments
  ADD COLUMN thumbnail    bytea,
  ADD COLUMN thumbnail_at timestamp with time zone,
  ADD CONSTRAINT garments_thumbnail_size CHECK (thumbnail IS NULL OR octet_length(thumbnail) <= 65536);
