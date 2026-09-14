"""Shared fixtures. Nothing here touches a database: these tests cover pure functions.

``src.data.db`` builds ``DB_CONFIG`` at import time, which reads ``Settings``,
which requires the database variables. Anything importing a service therefore
needs them present before the first import — including on CI, where there is no
``.env``. These defaults supply them without pointing at a real database:
nothing in the default suite opens a connection, and the integration tests that
do run against the compose stack, whose values come from the real environment.
``setdefault`` so a genuine environment always wins.
"""

import os

for key, value in {
    "ENV": "local",
    "DEBUG": "true",
    "DB_ENGINE": "tortoise.backends.asyncpg",
    "DB_HOST": "localhost",
    "DB_PORT": "5430",
    "DB_NAME": "db-anydm",
    "DB_USER": "user",
    "DB_PASSWORD": "password",
}.items():
    os.environ.setdefault(key, value)
