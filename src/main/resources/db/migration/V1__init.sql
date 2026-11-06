-- mcp-closet: closet 스키마 (specs/005-database/data-model.md 3절)
-- Flyway 설정: spring.flyway.schemas=closet, default-schema=closet, create-schemas=true
-- pgvector 타입은 public 스키마의 확장이라 public.vector로 적습니다(search_path와 상관없이 동작).
-- 사진은 저장하지 않습니다. 옷의 설명(JSON)과 그 설명의 임베딩만 남깁니다.

CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

-- 내 옷. 같은 옷이면 새로 만들지 않고 last_seen_at·description·embedding만 바꿈
CREATE TABLE garments (
  id             varchar(40)              PRIMARY KEY,   -- "c_" + 임의 문자열 (도구의 garmentId)
  user_id        uuid                     NOT NULL,      -- account.users.id (FK 없음)
  category       varchar(10)              NOT NULL,
  name           varchar(50)              NOT NULL,      -- 예: 니트
  color_name     varchar(30)              NOT NULL,      -- 예: 차콜 그레이
  color_family   varchar(10)              NOT NULL,
  tone           varchar(5)               NOT NULL,
  formality      varchar(10)              NOT NULL DEFAULT 'unknown',
  description    jsonb                    NOT NULL,      -- 마지막으로 본 Garment 전체(pattern·material·defects 등)
  embedding      public.vector(384),                     -- multilingual-e5-small. 같은 옷 찾기·closet_search
  first_seen_at  timestamp with time zone NOT NULL DEFAULT now(),
  last_seen_at   timestamp with time zone NOT NULL DEFAULT now(),
  created_at     timestamp with time zone NOT NULL DEFAULT now(),
  updated_at     timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT garments_category_check  CHECK (category IN ('top', 'bottom', 'outer', 'dress', 'shoes', 'other')),
  CONSTRAINT garments_color_family_check CHECK (color_family IN ('black', 'white', 'gray', 'navy', 'beige', 'brown', 'khaki',
                                                  'blue', 'red', 'pink', 'orange', 'yellow', 'green', 'purple', 'unknown')),
  CONSTRAINT garments_tone_check      CHECK (tone IN ('light', 'mid', 'dark')),
  CONSTRAINT garments_formality_check CHECK (formality IN ('formal', 'smart', 'casual', 'sporty', 'unknown')),
  CONSTRAINT garments_description_object CHECK (jsonb_typeof(description) = 'object'),
  CONSTRAINT garments_seen_order      CHECK (last_seen_at >= first_seen_at),
  CONSTRAINT garments_id_user_uq      UNIQUE (id, user_id)   -- wears가 (옷, 주인)을 함께 가리키게
);
-- 한 사람의 옷만 훑습니다(100벌 안팎). 벡터 근사 인덱스(HNSW)는 두지 않습니다:
-- 모든 사람의 옷에서 가까운 40개를 먼저 뽑은 뒤 user_id로 거르면 내 옷이 빠질 수 있어서입니다.
-- 내 옷만 골라 정확한 거리로 정렬하는 편이 빠르고 틀리지 않습니다(research.md R3).
CREATE INDEX garments_user_category_idx ON garments (user_id, category);
CREATE INDEX garments_user_seen_idx     ON garments (user_id, last_seen_at DESC);

-- 입은 날 (closet_wear). 같은 옷을 같은 날 두 번 기록하지 않음
CREATE TABLE wears (
  id          bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     uuid                     NOT NULL,
  garment_id  varchar(40)              NOT NULL,
  worn_on     date                     NOT NULL,
  created_at  timestamp with time zone NOT NULL DEFAULT now(),
  updated_at  timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT wears_garment_day_uq UNIQUE (garment_id, worn_on),
  -- 남의 옷에 내 입은 날이 붙지 않게: 옷과 주인을 함께 참조
  CONSTRAINT wears_garment_owner_fk FOREIGN KEY (garment_id, user_id)
    REFERENCES garments (id, user_id) ON DELETE CASCADE
);
CREATE INDEX wears_user_day_idx ON wears (user_id, worn_on DESC);

-- 코디 지식(RAG). 모두가 같이 쓰는 문서라 user_id 없음.
-- Spring AI PgVectorStore가 쓰는 표 모양(id, content, metadata, embedding) 그대로 둡니다.
--   spring.ai.vectorstore.pgvector: schema-name=closet, table-name=style_docs,
--   dimensions=384, distance-type=COSINE_DISTANCE, index-type=HNSW, initialize-schema=false
-- metadata 예: {"source":"style_docs/interview.md","chunkNo":3,"occasion":"formal"}
CREATE TABLE style_docs (
  id         uuid               PRIMARY KEY DEFAULT gen_random_uuid(),
  content    text               NOT NULL,
  metadata   json               NOT NULL,
  embedding  public.vector(384) NOT NULL
);
CREATE INDEX style_docs_embedding_idx ON style_docs USING hnsw (embedding public.vector_cosine_ops);
-- 같은 문서 조각을 두 번 넣지 않게(다시 넣을 때는 id를 source+chunkNo로 만든 UUID로 두면 PgVectorStore가 덮어씀)
CREATE UNIQUE INDEX style_docs_source_chunk_uq ON style_docs (((metadata::jsonb ->> 'source')), ((metadata::jsonb ->> 'chunkNo')));
