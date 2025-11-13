from tortoise import BaseDBAsyncClient

RUN_IN_TRANSACTION = True


async def upgrade(db: BaseDBAsyncClient) -> str:
    return """
        CREATE TABLE IF NOT EXISTS `media` (
    `id` CHAR(36) NOT NULL PRIMARY KEY,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `deleted_at` DATETIME(6),
    `name` VARCHAR(128) NOT NULL,
    `slug` VARCHAR(255) NOT NULL,
    `description` LONGTEXT,
    `meta` JSON,
    `platform_id` CHAR(36) NOT NULL,
    `file_id` CHAR(36) NOT NULL UNIQUE,
    CONSTRAINT `fk_media_platform_8d77fbc3` FOREIGN KEY (`platform_id`) REFERENCES `platform` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_media_file_559efad9` FOREIGN KEY (`file_id`) REFERENCES `file` (`id`) ON DELETE CASCADE,
    KEY `idx_media_created_ea8984` (`created_at`),
    KEY `idx_media_updated_e7e58c` (`updated_at`),
    KEY `idx_media_deleted_8f6d54` (`deleted_at`),
    KEY `idx_media_name_0f8f1d` (`name`),
    KEY `idx_media_slug_327911` (`slug`)
) CHARACTER SET utf8mb4 COMMENT='Media';"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        DROP TABLE IF EXISTS `media`;"""


MODELS_STATE = (
    "eJztmmtz2jgUhv+Kx5/SmW6mJU2TZTo744BDvQXcAdN223Q8whaOJrZEbXkJm+G/ryRs4w"
    "vmkoWEsP7C5ZwjIT1HkqUXPcgesaF7eo1cKNelBxkDj39Im19LMhiPEyP/TsFQxMujOGAY"
    "UB9YlNlGwA0gM9kwsHw0pohgHhlXZROLhSLsMCMOXZeZQox+hdCkxIH0FvrM8eMnMyNsw3"
    "sYxF/Hd+YIQdfONBPZvE5hN+l0LGyDgda8FpH854amRdzQw4vo8ZTeEpyEhyGyT3kZ7nMg"
    "hj6g0E51hLcy6m9smreYGagfwqSp9sJgwxEIXY5D/jAKscUpSOKX+Mu7P+QCoIjFEjwWwR"
    "wuwpSzeJjNe7Xos7DK/KcaH5Xeydn7V6KXJKCOL5yCiDwTBQEF86KC6wKk5UPebRPQItAm"
    "81DkweVQsyVzcO2o6Gn84TGQY8MKyjG9xyGVWRdsHbvTqOoViA2to/YNpfOZd8QLgl+uIK"
    "QYKvfUhHWas57MM0LYBJnPmqQS6atmfJT4V+m73lXzeUvijO8ybxMIKTExmZjATlGIrTEp"
    "FrnIazi2H5nXbMkqr8+Z16jxi7SydRg+Lq3ZkjtIa9Tap8vqC8lizGHl9AxI6FuwmMPGLf"
    "BVHHoihxrrPsDzsEwuF6VzeWS49jQhZZdYwC0+vuS23lDadUm4b/Cgxz6HPvv0ResZA+75"
    "G/k05L5GWx8065LlktAu2RHk57EH7k0XYofesq8XKwbAF6UnHoIXuZx2I0eNe2aZFHhsmM"
    "/BLc3C8lmUKfRk8P/japih+LZ2uQFHFlVKUviyLMX7Fhjj+BdJsHZ+vgFBFlVKUPiyBMeA"
    "1V0gaMB7upxgHL8vgovd/P4Xb/WbkVm3Y1AnHeXbq8za3da7rTg8BZatLFc5ngH6Z8mIvE"
    "KOhkuIxiVyRFkHdvIw3DVQ1iL29tvvtdrZ2UXtzdn7y/N3Fxfnl28uWaxoU9G1agW90lpa"
    "NweWG3KLJqSgiPXPvt4tWS+j+BzUAWa9/WEji76WXBTQnweJeAUt3uPVYzY/PHObCF4BH7"
    "P8hDu6Sx3NuGEIrLsJ8G2z4CE1UhabdaVTZqMlObuKyukYGoS99KALBKBituaaQCeu51Dz"
    "tLBGrRBwvZqXI+YBDBzREF4dL5zuYFEMSTpeqoYkiNfLIUlllR5S6SEHt885lnNzpYccZ1"
    "4rPeRI9ZD/1QFyL0fwwA2dbQjG8S+S4F6O4OmWFUCWn8RzxR7F86DONns5j1cHxx0fHDPi"
    "ETu6jYjvmdsdA3LFdnkeeFbGa7f/C3L8f+wtqaWKPO0J6rl4FSSK4sAr8rsmPkQO/gSnhb"
    "8ylqoLn1M1HexgKygMzOyDSXIez88o1sv5NlNQVvoNpanKs20UnuxILWKO1ZvNIMd3MQ50"
    "bK7Fm5p6G6AtamWba0HJcCzKQemRWqoIpSfGelEoXWWlC1W60MFtvo9FP6h0oePMa6ULHa"
    "kuVH4/Y/0tmSe/psGrhsDjuSk+4/tGT1U6WrdVl5KwG9zXG5rSNjtqU1OYg1gIuKb4O4n5"
    "DL2ntFQez+A7MHuD5gZf9XSlybY+hqh06BNgWyCgot6W0m6rvb/qkgNcF/rTG9wZ9LVGXf"
    "LCAFk3WG0OGoqh6V1+QwfaoSX+++O3dFpRI52ohV31a5+NNDgJSnYna6SujZSuFULXYV01"
    "2fdO+OiFwqcFWOmEL00nnMBhgOhWMzxVZCdQj2COI2u7sRnHV4OyEq8P7NZT7iJTUMxDfJ"
    "Pp+tPO7jAdkMQ42+ra10Yq1+xfbQdM4A=="
)
