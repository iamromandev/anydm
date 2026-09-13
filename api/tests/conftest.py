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

os.environ.setdefault("ENV", "local")
os.environ.setdefault("DEBUG", "true")
os.environ.setdefault("DB_HOST", "localhost")
os.environ.setdefault("DB_PORT", "5403")
os.environ.setdefault("DB_NAME", "anydm")
os.environ.setdefault("DB_USER", "user")
os.environ.setdefault("DB_PASSWORD", "password")
