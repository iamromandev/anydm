from tortoise.fields import Field


class UrlField(Field):
    SQL_TYPE = "TEXT"
