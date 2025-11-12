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
    `name` VARCHAR(255) NOT NULL,
    `path` LONGTEXT NOT NULL,
    `size` BIGINT,
    `mime_type` VARCHAR(128) NOT NULL,
    KEY `idx_file_created_3b1e63` (`created_at`),
    KEY `idx_file_updated_8f52e9` (`updated_at`),
    KEY `idx_file_deleted_005339` (`deleted_at`),
    KEY `idx_file_source_2324b0` (`source`),
    KEY `idx_file_name_7e5ab0` (`name`),
    KEY `idx_file_mime_ty_dbb449` (`mime_type`)
) CHARACTER SET utf8mb4 COMMENT='File';"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        DROP TABLE IF EXISTS `file`;"""


MODELS_STATE = (
    "eJztmP9v2jgUwP+VKD91Uq/a0nX00DQpQMZyAjJB2HYbU2Rik1p1bJY413IV//vZJoF8Ad"
    "pyLWsrfoHkvWfnvc/zc/xyo4cMInLyEROk17UbnYJQXuTFx5oOptOlUN5zMFb2+iQzGMc8"
    "Aj4XsgkgMRIiiGI/wlOOGZWW2VSQ+cIU00AIaUKIECUU/0qQx1mA+AWKhOLHTyHGFKJrFG"
    "e300tvghGBBTcxlHMqucdnUyUbDu3WR2UpHzf2fEaSkK6spzN+wejSPEkwPJFjpC5AFEWA"
    "I5gLRHqZxpuJFh4LAY8StHQVrgQQTUBCJA79/SShvqSgqSfJn7cf9AqglMUaPD6jEi6mXL"
    "K4mS+iWsWspLp8VPOT2T86ffdKRcliHkRKqYjoczUQcLAYqriuQPoRkmF7gFeBtoSG4xCt"
    "h1ocWYIL06En2cUukDPBFsoZvd2Q6iIE6FAyS6fegti1u9bANbufZSBhHP8iipDpWlJjKO"
    "msJD1aZISJAllUzXIS7avtftLkrfbd6VnlvC3t3O+69AkknHmUXXkA5ihk0oyUsFzlNZnC"
    "HfNaHHnI6+/Ma+r8Kq1iH0a7pbU48gHSmnq7v6w+kyxmHLaWZ8ySyEfVHDYvQGTRJFQ5tE"
    "X4gC7MCrlcjS7lUeB6pILUCfMBqb6+9I7TNDt1TalHdNgX10kkrr7YfXcoNf/giCdS1+w4"
    "w1Zd8wlL4IYTQbmOQ3DtEUQDfiFua1sWwBezr16CtVJOe6nCkJp5IQXqf20C1hdQZr835P"
    "9zDyywM87O7kBPWG3kp3RFglMg5q4QdNE1X08ws38sgqsT6ONvONY3t7DXZKCOuua3V4X9"
    "puP02pl5DqyohkaJZ4z/XbMiGziw6Qai2YgSURHAg2zgDw1UeCT+/vjTME5Pa8br03fnZ2"
    "9rtbPz1+fCVvlUVW2r+obdtnslsFJQxBqK99kCzT2qvTDoWZb8G+P8DiUvrDaWvNLN57IJ"
    "m1zmugcpGAP/8gpE0KtomME22VZVoRGWJYCCQNGRQcoIFi3pZwL4hEWhXu1Wl6rjLR3rNG"
    "90a9ean/LQuR461ydX6i+lwzl0ri8zr4fO9YV2rpsPUrf3rXs/T8mpEQhlbqrv+IHbt8yu"
    "3WvXtaXZiA6cpm12vK7Vsk2hYD4GxAsRxEDoXKdvti1pL+AHqNjTjmij75itpjlw1aTjiA"
    "Hog5iredtmp2P1/65rASAERbMR7Q4HdrOuhUmM/RG1WsOm6dpOT/bMCCY+kH7KvrmdOhmk"
    "HvasrwOx0tBVvEsX/ca406lwy6HwaTXSt59nnuShutD3kSS4D8DM/jkCfJQPEXnPKhw3f4"
    "8oDdsJ5/6b6H1/lbhC4xjze1V4bsiDQH0BNY79+63NzP6wKNcuyhBxUMX518Dpbfick9qX"
    "cA6piPMHxD4/1giO+c/nBldGvB1umWPpSCgnaPzujzzz/wBoZ2Mp"
)
