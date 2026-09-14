from tortoise import migrations
from tortoise.migrations import operations as ops
from tortoise.fields.base import OnDelete
from uuid import uuid4
from tortoise import fields

class Migration(migrations.Migration):
    dependencies = [('model', '0001_initial')]

    initial = False

    operations = [
        ops.CreateModel(
            name='TaskSegment',
            fields=[
                ('id', fields.UUIDField(primary_key=True, default=uuid4, unique=True, db_index=True)),
                ('created_at', fields.DatetimeField(db_index=True, auto_now=False, auto_now_add=True)),
                ('task', fields.ForeignKeyField('model.Task', source_field='task_id', db_constraint=True, to_field='id', related_name='segments', on_delete=OnDelete.CASCADE)),
                ('part', fields.CharField(max_length=16)),
                ('index', fields.IntField()),
                ('start_byte', fields.BigIntField()),
                ('end_byte', fields.BigIntField()),
                ('downloaded', fields.BigIntField(default=0, description='Bytes durably written, counted from ``start_byte``. Never ahead of disk.')),
            ],
            options={'table': 'task_segment', 'app': 'model', 'unique_together': (('task', 'part', 'index'),), 'pk_attr': 'id', 'table_description': 'Task segment'},
            bases=['StampBase'],
        ),
    ]
