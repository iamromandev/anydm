package dev.anydm.engine.tools

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ToolVersionTest {
    private fun v(text: String) = ToolVersion.parse(text)!!

    @Test
    fun parsesDottedNumbers() {
        v("2026.10.01").text shouldBe "2026.10.01"
        v("7.0.2").text shouldBe "7.0.2"
    }

    @Test
    fun comparesNumericallyNotAsText() {
        (v("2026.9.1") < v("2026.10.1")) shouldBe true
        (v("7.0.10") > v("7.0.9")) shouldBe true
        (v("2.1") < v("10.0")) shouldBe true
    }

    @Test
    fun missingTrailingPartsAreZero() {
        v("7.0") shouldBe v("7.0.0")
        (v("7.0.1") > v("7.0")) shouldBe true
    }

    @Test
    fun rejectsNonsense() {
        ToolVersion.parse("") shouldBe null
        ToolVersion.parse("abc") shouldBe null
        ToolVersion.parse("1..2") shouldBe null
        ToolVersion.parse("1.2.x") shouldBe null
    }

    @Test
    fun readsEachToolsOutput() {
        ToolVersion.fromOutput(Tool.YT_DLP, "2026.10.01\n") shouldBe v("2026.10.01")
        ToolVersion.fromOutput(
            Tool.FFMPEG,
            "ffmpeg version 7.0.2-static https://johnvansickle.com/ffmpeg/  Copyright (c) 2000-2024\nbuilt with gcc",
        ) shouldBe
            v("7.0.2")
        ToolVersion.fromOutput(Tool.FFMPEG, "ffprobe version 6.1 Copyright (c) 2007-2023") shouldBe v("6.1")
        ToolVersion.fromOutput(Tool.DENO, "deno 2.1.4 (stable, release, aarch64-apple-darwin)\nv8 13.0.245.12\ntypescript 5.6.2") shouldBe
            v("2.1.4")
    }

    @Test
    fun aGitBuildOfFfmpegHasNoVersion() {
        ToolVersion.fromOutput(Tool.FFMPEG, "ffmpeg version N-118000-gabcdef0 Copyright (c) 2000-2024") shouldBe null
    }

    @Test
    fun outputOfTheWrongShapeHasNoVersion() {
        ToolVersion.fromOutput(Tool.DENO, "command not found") shouldBe null
        ToolVersion.fromOutput(Tool.YT_DLP, "") shouldBe null
    }

    @Test
    fun readsTheBannersOfTheBuildsWePin() {
        ToolVersion.fromOutput(
            Tool.FFMPEG,
            "ffmpeg version n8.1.3-9-g29e619e767-20260930 Copyright (c) 2000-2026 the FFmpeg developers",
        ) shouldBe
            v("8.1.3")
        ToolVersion.fromOutput(Tool.FFMPEG, "ffprobe version n7.1 Copyright (c) 2007-2024 the FFmpeg developers") shouldBe v("7.1")
        ToolVersion.fromOutput(Tool.FFMPEG, "ffmpeg version N-127032-g6ae491a26c Copyright") shouldBe null
    }

    @Test
    fun ffmpegIsAskedForItsVersionWithASingleDash() {
        Tool.FFMPEG.versionArgument shouldBe "-version"
        Tool.YT_DLP.versionArgument shouldBe "--version"
        Tool.DENO.versionArgument shouldBe "--version"
    }
}
