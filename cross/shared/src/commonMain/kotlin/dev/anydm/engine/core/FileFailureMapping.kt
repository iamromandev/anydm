package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.StepResult

/** Spec section 5: only a full disk and a busy file have a class; the rest are local problems and the job fails with the reason. */
internal fun FileResult.Failed.toStepFailed(): StepResult.Failed {
    val (errorClass, what) =
        when (this) {
            is FileResult.DiskFull -> ErrorClass.DISK_FULL to "no room left on the disk for $path"
            is FileResult.Busy -> ErrorClass.TRANSIENT to "another program is using $path"
            is FileResult.AccessDenied -> null to "no permission to write $path"
            is FileResult.NotFound -> null to "$path doesn't exist"
            is FileResult.AlreadyExists -> null to "something is in the way at $path"
            is FileResult.InvalidName -> null to "the system can't make a file named $path"
            is FileResult.CrossVolume -> null to "$path can't be renamed to $target on another drive"
            is FileResult.TrashUnavailable -> null to "there is no trash for $path"
            is FileResult.IoError -> null to "couldn't use $path"
        }
    return StepResult.Failed(errorClass, if (detail == null) what else "$what: $detail")
}
