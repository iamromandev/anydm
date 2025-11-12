from tortoise import BaseDBAsyncClient

RUN_IN_TRANSACTION = True


async def upgrade(db: BaseDBAsyncClient) -> str:
    return """
        CREATE TABLE IF NOT EXISTS `aerich` (
    `id` INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    `version` VARCHAR(255) NOT NULL,
    `app` VARCHAR(100) NOT NULL,
    `content` JSON NOT NULL
) CHARACTER SET utf8mb4;"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        """


MODELS_STATE = (
    "eJzVlN1LwzAUxf+V0ScFlVmnDt/mQFR0g/mBMKRk7V0XliY1ufUD2f9ubrotXadDwQd9W8"
    "89Sc75kew9yFQCYq8DmseT4KTxHkiWgf2xOthpBCzPlzJ9IxsJZ2TeMjKoWYxWHTNhwEoJ"
    "mFjzHLmSVpWFECSq2Bq5TL1USP5UQIQqBZyAtoPho5W5TOAVzOIzn0ZjDiJZCcoTOtvpEb"
    "7lTruQeOaMdNooipUoMunN+RtOlFy6uURSU5CgGQJtj7qg+JRu3nPRqEzqLWXEypoExqwQ"
    "WKn7TQaxksTPpjGuYEqn7Ib7reNW++Co1bYWl2SpHM/Ker57udAR6N0GMzdnyEqHw+i5PY"
    "M2FGkNXnfC9Of0KktqCG3wOsIFsE0MF4KH6C/OL1HM2GskQKZI1zs8PNzA7L4z6J53BlvW"
    "tU1tlL3M5R3vzUdhOSOwHiS9jB9AnNv/J8D9ZvMbAK3rS4ButgrQnohQvsFViJc3/d7nEC"
    "tLaiDvpC04THiMOw3BDT7+TawbKFJrCp0Z8ySq8LauOw91rt2r/qmjoAym2u3iNji1jOkv"
    "czytPH4SRiyevjCdRGsTFaqvvOujLMzqCpMsdayo8Wz2Ae9rBcg="
)
