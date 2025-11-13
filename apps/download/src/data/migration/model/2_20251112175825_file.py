from tortoise import BaseDBAsyncClient

RUN_IN_TRANSACTION = True


async def upgrade(db: BaseDBAsyncClient) -> str:
    return """
        CREATE TABLE IF NOT EXISTS `file` (
    `id` CHAR(36) NOT NULL PRIMARY KEY,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `deleted_at` DATETIME(6),
    `source` VARCHAR(7) NOT NULL COMMENT 'LOCAL: local\nURL: url\nVIRTUAL: virtual\nCLOUD: cloud' DEFAULT 'local',
    `mime_type` VARCHAR(128) NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `path` LONGTEXT NOT NULL,
    `size` BIGINT,
    `meta` JSON,
    KEY `idx_file_created_3b1e63` (`created_at`),
    KEY `idx_file_updated_8f52e9` (`updated_at`),
    KEY `idx_file_deleted_005339` (`deleted_at`),
    KEY `idx_file_source_2324b0` (`source`),
    KEY `idx_file_mime_ty_dbb449` (`mime_type`),
    KEY `idx_file_name_7e5ab0` (`name`)
) CHARACTER SET utf8mb4 COMMENT='File';"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        DROP TABLE IF EXISTS `file`;"""


MODELS_STATE = (
    "eJztmP9v2jgUwP+VKD91Uq/a0nX00HRSChnLCcgEYdttTJGJTWrVcVhir+Wq/u+zTQL5Ar"
    "TlSo8ifoHkfXHe+zzbid+tHkYQkZMPmCC9rt3qFITyIi8+1nQwmcyF8p6BkbLXx5nBKGEx"
    "8JmQjQFJkBBBlPgxnjAcUWmZDQUjX5hiGggh5YQIEaf4J0ceiwLELlEsFN9/CDGmEN2gJL"
    "udXHljjAgshImhHFPJPTadKNlgYDc/KEv5uJHnR4SHdGE9mbLLiM7NOcfwRPpIXYAoigFD"
    "MJeIjDLNNxPNIhYCFnM0DxUuBBCNAScSh/5+zKkvKWjqSfLn7V96BVDKYgkeP6ISLqZMsr"
    "i9m2W1yFlJdfmoxkezd3T67pXKMkpYECulIqLfKUfAwMxVcV2A9GMk0/YAqwJtCg3DIVoO"
    "tehZggtT15PsYhPImWAN5YzeZkh1kQJ0KJmmQ69B7Nodq++anU8ykTBJfhJFyHQtqTGUdF"
    "qSHs0qEokFMls180G0L7b7UZO32jena5XrNrdzv+kyJsBZ5NHo2gMwRyGTZqSE5aKufAI3"
    "rGvR81DX/7OuafCLsop9GG1W1qLnE5Q1jfb5qvpCqphxWLs8k4jHPqrWsHEJYovyUNXQFu"
    "kDOjMr1HLhXaqjwLWlBamTyAek+vrS207DbNc1pR7SQU9c81hcfbZ77kBqfuGYcalrtJ1B"
    "s675JOJwxRdBeR2H4MYjiAbsUtzW1kyAz2ZPvQRrpZp2U4UhNXeFEoRims/ALa3C8lVUcH"
    "o2+P9xNyxQfGOcP4CjsFpJUumKLNX/IzBm9i+SoHF29gCCwmolQaUrEpwAMXaFoItu2HKC"
    "mf22CC6+5re/eVtf3cK+nYE66phfXxX27rbTbWXmObBiZ7ko8Uzwv0tm5AUObLqCaOZRIi"
    "oSeJKX4VMDFRGJvz/+NIzT05rx+vTd+dnbWu3s/PW5sFUxVVXrdtALu2V3S2CloLRpIgaq"
    "WP/uO90V+2VqX4I6oCLb7xD77FgjOGE/dhLxGloy4/Vztjw9Sx8RcgA5Z+UJd3yVO5pJwQ"
    "j4V9cghl5FExnRKtuqKjTCsgRQEChWMmOZ3+y8/4kANo7iUK+2Auaq4zXtgEne6N6WQH7I"
    "Q1vg0BbYudf9vhwfD22B/azroS2wp22B1cfR+5sCz34qlUMjEMraVN/xfbdnmR2726prc7"
    "Mh7TsN22x7Hatpm0IR+RgQL0QQA6FznZ7ZsqS9gB+gYsNgSC96jtlsmH1XDTqKIwB9kDA1"
    "bstst63eP3UtAISgeDqknUHfbtS1kCfYH1KrOWiYru10ZUMCQe4DGadsSrTSIIM0wq71pS"
    "9mGrpONmlRvDEedLZec7TerZP1/d8zO9+aSAgPHgMws3+JALfSmchHVuG4ukFRctsI504d"
    "+bbSprhGowSzR63wnMuTQN2DNY79x83NzP4wKZdOykOTZ5+aPHe/AVxe0aQ="
)
