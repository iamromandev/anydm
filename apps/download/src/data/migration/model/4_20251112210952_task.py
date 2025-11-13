from tortoise import BaseDBAsyncClient

RUN_IN_TRANSACTION = True


async def upgrade(db: BaseDBAsyncClient) -> str:
    return """
        CREATE TABLE IF NOT EXISTS `task` (
    `id` CHAR(36) NOT NULL PRIMARY KEY,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `deleted_at` DATETIME(6),
    `state` VARCHAR(15) NOT NULL COMMENT 'NEW: new\nPENDING: pending\nQUEUED: queued\nSCHEDULED: scheduled\nINITIALIZING: initializing\nSTARTING: starting\nRUNNING: running\nVALIDATING: validating\nTRANSFORMING: transforming\nWAITING: waiting\nBLOCKED: blocked\nDEFERRED: deferred\nRETRYING: retrying\nSUSPENDED: suspended\nRESUMED: resumed\nSKIPPED: skipped\nTIMEOUT: timeout\nFAILED: failed\nABORTED: aborted\nCANCELED: canceled\nSUCCESS: success\nPARTIAL_SUCCESS: partial_success\nCOMPLETED: completed\nSTALE: stale\nEXPIRED: expired\nARCHIVED: archived' DEFAULT 'new',
    `action` VARCHAR(9) NOT NULL COMMENT 'CREATE: create\nREAD: read\nUPDATE: update\nDELETE: delete\nUPSERT: upsert\nPATCH: patch\nFETCH: fetch\nDOWNLOAD: download\nUPLOAD: upload\nPARSE: parse\nEXTRACT: extract\nTRANSFORM: transform\nLOAD: load\nVALIDATE: validate\nCLEAN: clean\nTRAIN: train\nEVALUATE: evaluate\nINFER: infer\nPREDICT: predict\nDEPLOY: deploy\nRETRAIN: retrain\nSTART: start\nSTOP: stop\nRESTART: restart\nRETRY: retry\nCANCEL: cancel\nPAUSE: pause\nRESUME: resume\nBACKUP: backup\nRESTORE: restore\nARCHIVE: archive\nLOG: log\nALERT: alert\nMONITOR: monitor\nAUDIT: audit\nREPORT: report\nSEND: send\nRECEIVE: receive\nPUBLISH: publish\nSUBSCRIBE: subscribe\nACK: ack\nNACK: nack' DEFAULT 'extract',
    `input` JSON,
    `output` JSON,
    `meta` JSON,
    KEY `idx_task_created_afb134` (`created_at`),
    KEY `idx_task_updated_57b925` (`updated_at`),
    KEY `idx_task_deleted_938c7f` (`deleted_at`)
) CHARACTER SET utf8mb4 COMMENT='Task';"""


async def downgrade(db: BaseDBAsyncClient) -> str:
    return """
        DROP TABLE IF EXISTS `task`;"""


MODELS_STATE = (
    "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"
    "Zzf/AkDpiaw="
)
