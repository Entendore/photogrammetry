package com.example.photogrammetry

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.FloatBuffer

class PointCloudRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var camPosUniform = 0
    private var sizeUniform = 0

    private var vbo = 0
    private var vboCapacityPoints = 20_000
    private var pointCount = 0
    private val bytesPerPoint = 12
    private val mvpMatrix = FloatArray(16)

    fun createOnGlThread() {
        program = ShaderUtil.link(
            ShaderUtil.compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER),
            ShaderUtil.compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        camPosUniform = GLES20.glGetUniformLocation(program, "u_CamPos")
        sizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
            vboCapacityPoints * bytesPerPoint, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun update(points: FloatBuffer, numPoints: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val bytes = numPoints * bytesPerPoint
        if (bytes > vboCapacityPoints * bytesPerPoint) {
            vboCapacityPoints = numPoints * 2
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                vboCapacityPoints * bytesPerPoint, null, GLES20.GL_DYNAMIC_DRAW)
        }
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, bytes, points)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        pointCount = numPoints
    }

    fun draw(projection: FloatArray, view: FloatArray, camPos: FloatArray) {
        if (pointCount == 0) return
        Matrix.multiplyMM(mvpMatrix, 0, projection, 0, view, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, bytesPerPoint, 0)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(camPosUniform, camPos[0], camPos[1], camPos[2])
        GLES20.glUniform1f(sizeUniform, 6f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            uniform vec3 u_CamPos;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_Mvp * a_Position;
                gl_PointSize = u_PointSize;
                float d = distance(a_Position.xyz, u_CamPos);
                float t = clamp((d - 0.3) / 3.5, 0.0, 1.0);
                v_Color = mix(vec3(0.25, 1.0, 0.45), vec3(1.0, 0.55, 0.10), t);
            }"""
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            void main() { gl_FragColor = vec4(v_Color, 1.0); }"""
    }
}
