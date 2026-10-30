class BaseMixin:
    @property
    def _tag(self) -> str:
        return self.__class__.__name__
