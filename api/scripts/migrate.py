import asyncio

from src.data.db import run_migration


def main() -> None:
    asyncio.run(run_migration())


if __name__ == "__main__":
    main()
