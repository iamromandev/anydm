from tortoise import BaseDBAsyncClient

RUN_IN_TRANSACTION = True


async def upgrade(db: BaseDBAsyncClient) -> str:
    return """
        CREATE TABLE IF NOT EXISTS `platform` (
    `id` CHAR(36) NOT NULL PRIMARY KEY,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `deleted_at` DATETIME(6),
    `type` VARCHAR(12) NOT NULL COMMENT 'STREAMING: streaming\nSOCIAL_MEDIA: social_media\nSTORAGE: storage\nCLOUD: cloud\nBROADCASTING: broadcasting\nGALLERY: gallery\nMUSIC: music\nEDUCATIONAL: educational\nGAMING: gaming\nNEWS: news' DEFAULT 'streaming',
    `name` VARCHAR(128) NOT NULL UNIQUE,
    `slug` VARCHAR(255) NOT NULL UNIQUE,
    `description` LONGTEXT,
    `website` VARCHAR(128) UNIQUE,
    `icon` LONGTEXT,
    `meta` JSON,
    KEY `idx_platform_created_d8e9d2` (`created_at`),
    KEY `idx_platform_updated_0075fd` (`updated_at`),
    KEY `idx_platform_deleted_068b92` (`deleted_at`),
    KEY `idx_platform_type_29e6ed` (`type`),
    KEY `idx_platform_name_db4c6d` (`name`),
    KEY `idx_platform_slug_472619` (`slug`),
    KEY `idx_platform_website_ffc119` (`website`)
) CHARACTER SET utf8mb4 COMMENT='Platform';"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        DROP TABLE IF EXISTS `platform`;"""


MODELS_STATE = (
    "eJztmPFP4jAUx/+VZT9pYkzkzoshl0sm7HAXYAbG6SlmKWuZjV2LWxskhv/92rIxtgEqp3"
    "fx4i/K3vu+7r3Pe10Hj2bEICKH5wTwMYsjs248mhRESH4ouw4ME0wmS4e65mBEtHSyKhol"
    "PAYBl/YxIAmSJoiSIMYTjhlV6tUlIQukHNNQOqggRJoExfcC+ZyFiN+iWDqub6QZU4geUJ"
    "JdTu78MUYEFlLGUK2p7T6fTbRtMHCa37VS3W7kB4yIiObqyYzfMrqUC4HhoYpRvhBRFAOO"
    "4EoxKsu07sy0yFgaeCzQMlWYGyAaA0EUEvPrWNBAkTD0ndSfz9/MCqSUxRo8AaMKMKZcsX"
    "icL6rKa9ZWU92qcWb19j592ddVsoSHsXZqIuZcBwIOFqGaaw4yiJEq2we8CrQpPRxHaD3U"
    "YmQJLkxDD7MPu0DODFsoZ/R2Q2rKEqBLySxdegtiz+nYfc/qnKtCoiS5J5qQ5dnKU9PWWc"
    "m6t+gIk5tksXuWixgXjndmqEvjyu3a5b4tdd6VqXICgjOfsqkP4AqFzJqRksq8r2ICd+xr"
    "MfKjr/+yr2nyeVvl8xjt1tZi5Cu0Nc3273X1nXQx47B1e2rwlQ42bkFsUxHpDjqyeEADVO"
    "lkFlvqoUT1RptRLY1ApHpTPeP7Xs+2Ok63VTeWsiHtuw3Havsdu+lY0sECDIgfIYiB9Hlu"
    "z2rZSi/hh2hIG2130KwbAWECDulpz7WaDavv6UVHMQMwAAnX67asdtvu/aobISAExbMh7Q"
    "z6TqNuRCLBwZDazUHD8hy3a7XrBoIiACpPQFTkIskwzbBrX/TlpKFpsuHtpPxMicCDTxAN"
    "+a28PKptmcafVk+fyEe10oR1U09Nu+aFidD/107E+v2c6V9nCp5+n/nDJ3KJ3smz8J1s4X"
    "dSBpgQEb4EYKZ/jwBrx8fPAChVGwFq37x0tuSZVTh66IFvOlgKYTvhfPosyb9hvP1hYl96"
    "hXMko7bXsS73C2dJ2+22MvkKZfk8Oy3BnaJRgvmLdvhKyKtA/Q/2OA5eNpuZ/mMo1w5lhD"
    "io4vzRd7vrcWb6Es4BlXVeQxzwA4PghN+8N7iq4u1wyxxLr4RqAQVX/V4xvlv5oq0MIxDc"
    "TUEM/YqH1dgmbdUV1aKyBVD5+gTTiufz37M4uac="
)
