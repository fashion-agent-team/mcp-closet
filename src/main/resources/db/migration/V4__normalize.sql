-- 정규화(2026-11-20, 강사님 피드백)
-- 1) description(jsonb) 풀기: 옷 한 벌에 하나인 값(무늬·소재·메모·보조색)은 칸으로, 여러 개인 얼룩·찢어짐은 garment_defects로(1NF).
--    사진 속 위치(box·position)·인식 확신도는 그 사진 한 장의 값이라 옷장에 두지 않습니다.
-- 2) wears.user_id 빼기: garment_id만 알면 주인이 정해져서 중복이었습니다(3NF). 그래서 garments의 (id, user_id) UNIQUE도 필요 없어집니다.

ALTER TABLE garments ADD COLUMN pattern            varchar(30);
ALTER TABLE garments ADD COLUMN pattern_checked_by varchar(5);
ALTER TABLE garments ADD COLUMN pattern_guess      varchar(30);
ALTER TABLE garments ADD COLUMN material           varchar(50);
ALTER TABLE garments ADD COLUMN notes              varchar(500);
ALTER TABLE garments ADD COLUMN sub_color_name     varchar(30);
ALTER TABLE garments ADD COLUMN sub_color_family   varchar(10);

UPDATE garments SET
  pattern            = left(NULLIF(description ->> 'pattern', ''), 30),
  pattern_checked_by = CASE WHEN description ->> 'patternCheckedBy' IN ('model', 'gpt') THEN description ->> 'patternCheckedBy' END,
  pattern_guess      = left(NULLIF(description ->> 'patternGuess', ''), 30),
  material           = left(NULLIF(description ->> 'material', ''), 50),
  notes              = left(NULLIF(description ->> 'notes', ''), 500),
  sub_color_name     = left(NULLIF(description ->> 'subColorName', ''), 30),
  sub_color_family   = left(NULLIF(lower(description ->> 'subColorFamily'), ''), 10);

ALTER TABLE garments ADD CONSTRAINT garments_pattern_checked_by_check CHECK (pattern_checked_by IN ('model', 'gpt'));

CREATE TABLE garment_defects (
  id          bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  garment_id  varchar(40)              NOT NULL REFERENCES garments (id) ON DELETE CASCADE,
  kind        varchar(20)              NOT NULL,   -- stain·tear 등
  location    varchar(50),                         -- 어디쯤. 예: 왼쪽 소매
  confidence  numeric(4,3),
  checked_by  varchar(5),                          -- model | gpt
  created_at  timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT garment_defects_checked_by_check CHECK (checked_by IN ('model', 'gpt')),
  CONSTRAINT garment_defects_confidence_check CHECK (confidence BETWEEN 0 AND 1)
);
CREATE INDEX garment_defects_garment_idx ON garment_defects (garment_id);

INSERT INTO garment_defects (garment_id, kind, location, confidence, checked_by)
SELECT g.id,
       left(d ->> 'kind', 20),
       left(NULLIF(d ->> 'where', ''), 50),
       CASE WHEN jsonb_typeof(d -> 'confidence') = 'number' AND (d ->> 'confidence')::numeric BETWEEN 0 AND 1
            THEN round((d ->> 'confidence')::numeric, 3) END,
       CASE WHEN d ->> 'checkedBy' IN ('model', 'gpt') THEN d ->> 'checkedBy' END
  FROM garments g
 CROSS JOIN LATERAL jsonb_array_elements(
         CASE WHEN jsonb_typeof(g.description -> 'defects') = 'array' THEN g.description -> 'defects' ELSE '[]'::jsonb END) d
 WHERE NULLIF(d ->> 'kind', '') IS NOT NULL;

ALTER TABLE garments DROP CONSTRAINT garments_description_object;
ALTER TABLE garments DROP COLUMN description;

ALTER TABLE wears DROP CONSTRAINT wears_garment_owner_fk;
DROP INDEX wears_user_day_idx;
ALTER TABLE wears DROP COLUMN user_id;
ALTER TABLE wears ADD CONSTRAINT wears_garment_fk FOREIGN KEY (garment_id) REFERENCES garments (id) ON DELETE CASCADE;
CREATE INDEX wears_day_idx ON wears (worn_on DESC);
ALTER TABLE garments DROP CONSTRAINT garments_id_user_uq;
