package com.petesburgh.shooter

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.nuklear.*
import org.lwjgl.nuklear.NkConvertConfig.VERTEX_LAYOUT
import org.lwjgl.nuklear.Nuklear.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL14.GL_FUNC_ADD
import org.lwjgl.opengl.GL14.glBlendEquation
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.stb.STBTTAlignedQuad
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTTPackContext
import org.lwjgl.stb.STBTTPackedchar
import org.lwjgl.stb.STBTruetype.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Platform
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.properties.Delegates

class Gui constructor(window: Long) {
    private var window by Delegates.notNull<Long>()
    private var context by Delegates.notNull<NkContext>()
    private var commands by Delegates.notNull<NkBuffer>()
    private var defaultFont by Delegates.notNull<NkUserFont>()
    private var nullTexture by Delegates.notNull<NkDrawNullTexture>()

    private var program by Delegates.notNull<Int>()
    private var vertexShader by Delegates.notNull<Int>()
    private var fragmentShader by Delegates.notNull<Int>()
    private var uniformTexture by Delegates.notNull<Int>()
    private var uniformProjection by Delegates.notNull<Int>()

    init {
        this.window = window
        commands = NkBuffer.create()
        defaultFont = NkUserFont.create()
        nullTexture = NkDrawNullTexture.create()

        // this must occur after the other variables are initialized
        context  = initialize(window)
    }

    companion object {
        const val MAX_VERTEX_BUFFER = 512 * 1024L
        const val MAX_ELEMENT_BUFFER = 128 * 1024L
        const val NK_BUFFER_DEFAULT_INITIAL_SIZE = 4 * 1024L

        val ALLOCATOR = NkAllocator.create()

        init {
            ALLOCATOR.alloc(fun(_, _, size): Long {
                val mem = nmemAlloc(size)
                if (mem == NULL) {
                    throw OutOfMemoryError()
                }

                return mem
            })
            ALLOCATOR.mfree(fun(_, ptr) = nmemFree(ptr))
        }
    }

    fun getNewFrame(): Frame {
        nk_input_begin(context)

        when {
            context.input().mouse().grab() -> glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)
            context.input().mouse().grabbed() -> glfwSetCursorPos(window,
                    context.input().mouse().prev().x() as Double,
                    context.input().mouse().prev().y() as Double)
            context.input().mouse().ungrab() -> glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
        }

        nk_input_end(context)

        MemoryStack.stackPush().use {
            val w = it.mallocInt(1)
            val h = it.mallocInt(1)

            glfwGetWindowSize(window, w, h)
            val width = w.get(0)
            val height = h.get(0)

            glfwGetFramebufferSize(window, w, h)
            val displayWidth = w.get(0)
            val displayHeight = h.get(0)

            return Frame(width, height, displayWidth, displayHeight)
        }
    }

    fun render(frame: Frame, useAA: Boolean) {
        glEnable(GL_BLEND)
        glBlendEquation(GL_FUNC_ADD)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_SCISSOR_TEST)
        glActiveTexture(GL_TEXTURE0)

        // setup program
        glUseProgram(program)
        glUniform1i(uniformTexture, 0)

        MemoryStack.stackPush().use {
            glUniformMatrix4fv(uniformProjection, false, it.floats(
                    2.0f / frame.width, 0.0f, 0.0f, 0.0f,
                    0.0f, -2.0f / frame.height, 0.0f, 0.0f,
                    0.0f, 0.0f, -1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f, 1.0f
            ))
        }

        glViewport(0, 0, frame.displayWidth, frame.displayHeight)

        glBufferData(GL_ARRAY_BUFFER, MAX_VERTEX_BUFFER, GL_STREAM_DRAW)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, MAX_ELEMENT_BUFFER, GL_STREAM_DRAW)

        val vertices = glMapBuffer(GL_ARRAY_BUFFER, GL_WRITE_ONLY, MAX_VERTEX_BUFFER, null)
        val elements = glMapBuffer(GL_ELEMENT_ARRAY_BUFFER, GL_WRITE_ONLY, MAX_ELEMENT_BUFFER, null)

        val aa = when (useAA) {
            true -> NK_ANTI_ALIASING_ON
            else -> NK_ANTI_ALIASING_OFF
        }

        MemoryStack.stackPush().use {
            val config = with (NkConvertConfig.callocStack(it)) {
                vertex_alignment(4)
                vertex_size(20)
                vertex_layout(VERTEX_LAYOUT)
                null_texture(nullTexture)
                circle_segment_count(22)
                curve_segment_count(22)
                arc_segment_count(22)
                global_alpha(1.0f)
                shape_AA(aa)
                line_AA(aa)
                this
            }

            val vbuf = NkBuffer.mallocStack(it)
            val ebuf = NkBuffer.mallocStack(it)

            nk_buffer_init_fixed(vbuf, vertices)
            nk_buffer_init_fixed(ebuf, elements)
            nk_convert(context, commands, vbuf, ebuf, config)
        }

        glUnmapBuffer(GL_ELEMENT_ARRAY_BUFFER)
        glUnmapBuffer(GL_ARRAY_BUFFER)

        var offset: Long = NULL
        var cmd = nk__draw_begin(context, commands)
        while (cmd != null) {
            if (cmd.elem_count() == 0) {
                continue
            }

            glBindTexture(GL_TEXTURE_2D, cmd.texture().id())
            glScissor(
                    (cmd.clip_rect().x() * frame.scaleX) as Int,
                    ((frame.height - cmd.clip_rect().y() + cmd.clip_rect().h()) * frame.scaleY) as Int,
                    (cmd.clip_rect().w() * frame.scaleX) as Int,
                    (cmd.clip_rect().h() * frame.scaleY) as Int)
            glDrawElements(GL_TRIANGLES, cmd.elem_count(), GL_UNSIGNED_SHORT, offset)
            offset += cmd.elem_count() * 2

            cmd = nk__draw_next(cmd, commands, context)
        }

        nk_clear(context)

        // default OpenGL state
        glUseProgram(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
        glDisable(GL_BLEND)
        glDisable(GL_SCISSOR_TEST)
    }

    fun shutdown() {
        nk_free(context)
        glDetachShader(program, vertexShader)
        glDetachShader(program, fragmentShader)
        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)
        glDeleteProgram(program)
        glDeleteTextures(defaultFont.texture().id())
        glDeleteTextures(nullTexture.texture().id())
        nk_buffer_free(commands)
        defaultFont.query().free()
        defaultFont.width().free()
    }

    private fun initialize(window: Long): NkContext {
        val context = NkContext.create()
        val offset = NkVec2.create()
        glfwSetScrollCallback(window, fun(_, xoffset, yoffset) = nk_input_scroll(context, offset.set(xoffset as Float, yoffset as Float)))
        glfwSetCharCallback(window, fun(_, codepoint) = nk_input_unicode(context, codepoint))
        glfwSetKeyCallback(window, fun(_, key, scancode, action, mods) {
            when (key) {
                GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true)
                GLFW_KEY_DELETE -> nk_input_key(context, NK_KEY_DEL, action == GLFW_PRESS)
                GLFW_KEY_ENTER -> nk_input_key(context, NK_KEY_ENTER, action == GLFW_PRESS)
                GLFW_KEY_TAB -> nk_input_key(context, NK_KEY_TAB, action == GLFW_PRESS)
                GLFW_KEY_BACKSPACE -> nk_input_key(context, NK_KEY_BACKSPACE, action == GLFW_PRESS)
                GLFW_KEY_UP -> nk_input_key(context, NK_KEY_UP, action == GLFW_PRESS)
                GLFW_KEY_DOWN -> nk_input_key(context, NK_KEY_DOWN, action == GLFW_PRESS)
                GLFW_KEY_HOME -> nk_input_key(context, NK_KEY_TEXT_START, action == GLFW_PRESS)
                GLFW_KEY_END -> nk_input_key(context, NK_KEY_TEXT_END, action == GLFW_PRESS)
                GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> nk_input_key(context, NK_KEY_SHIFT, action == GLFW_PRESS)
                GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> {
                    if (action == GLFW_PRESS) {
                        nk_input_key(context, NK_KEY_COPY, glfwGetKey(window, GLFW_KEY_C) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_PASTE, glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_CUT, glfwGetKey(window, GLFW_KEY_X) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_UNDO, glfwGetKey(window, GLFW_KEY_Z) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_REDO, glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_WORD_LEFT, glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_WORD_RIGHT, glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_LINE_START, glfwGetKey(window, GLFW_KEY_B) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_TEXT_LINE_END, glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS)
                    } else {
                        nk_input_key(context, NK_KEY_LEFT, glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_RIGHT, glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS)
                        nk_input_key(context, NK_KEY_COPY, false)
                        nk_input_key(context, NK_KEY_PASTE, false)
                        nk_input_key(context, NK_KEY_CUT, false)
                        nk_input_key(context, NK_KEY_SHIFT, false)
                    }
                }
            }
        })
        glfwSetCursorPosCallback(window, fun(_, xpos, ypos) = nk_input_motion(context, xpos as Int, ypos as Int))
        glfwSetMouseButtonCallback(window, fun(_, button, action, mods) {
            MemoryStack.stackPush().use {
                val cx = it.mallocDouble(1)
                val cy = it.mallocDouble(1)

                glfwGetCursorPos(window, cx, cy)

                val x = cx.get(0) as Int
                val y = cy.get(0) as Int

                val nkButton = when(button) {
                    GLFW_MOUSE_BUTTON_RIGHT -> NK_BUTTON_RIGHT
                    GLFW_MOUSE_BUTTON_MIDDLE -> NK_BUTTON_MIDDLE
                    else -> NK_BUTTON_LEFT
                }

                nk_input_button(context, nkButton, x, y, action == GLFW_PRESS)
            }
        })

        nk_init(context, ALLOCATOR, null)
        context.clip().copy(fun(handle, text, len) {
            if (len == 0) {
                return
            }

            MemoryStack.stackPush().use {
                val str = it.malloc(len + 1)
                memCopy(text, memAddress(str), len as Long)
                str.put(len, 0)

                glfwSetClipboardString(window, str)
            }
        })
        context.clip().paste(fun(_, edit) {
            val text = nglfwGetClipboardString(window)
            if (text != NULL) {
                nnk_textedit_paste(edit, text, nnk_strlen(text))
            }
        })

        createDevice()
        createDefaultFont()
        return context
    }

    private fun createDevice() {
        val shaderVersion = when (Platform.get()) {
            Platform.MACOSX -> "#version 150"
            else -> "#version 300 es"
        }

        val vertexShaderSource = """
            $shaderVersion
            uniform mat4 Projection;
            in vec2 Position;
            in vec2 TexCoord;
            in vec4 Color;
            out vec2 Frag_UV;
            out vec4 Frag_Color;
            void main() {
                Frag_UV = TexCoord;
                Frag_Color = Color;
                gl_Position = Projection * vec4(Position.xy, 0, 1);
            }
            """.trimIndent()

        val fragmentShaderSource = """
            $shaderVersion
            precision mediump float;
            uniform sampler2D Texture;
            in vec2 Frag_UV;
            in vec4 Frag_Color;
            out vec4 Out_Color;
            void main() {
                Out_Color = Frag_Color * texture(Texture, Frag_UV.st);
            }
            """.trimIndent()

        nk_buffer_init(commands, ALLOCATOR, NK_BUFFER_DEFAULT_INITIAL_SIZE)

        program = glCreateProgram()
        vertexShader = glCreateShader(GL_VERTEX_SHADER)
        fragmentShader = glCreateShader(GL_FRAGMENT_SHADER)

        val attachShader = fun(shader: Int, shaderSource: String) {
            glShaderSource(shader, shaderSource)
            glCompileShader(shader)
            if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
                throw IllegalStateException(glGetShaderInfoLog(shader))
            }
            glAttachShader(program, shader)
        }

        attachShader(vertexShader, vertexShaderSource)
        attachShader(fragmentShader, fragmentShaderSource)

        glLinkProgram(program)
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            throw IllegalStateException()
        }

        uniformTexture = glGetUniformLocation(program, "Texture")
        uniformProjection = glGetUniformLocation(program, "Projection")
        val attribPosition = glGetAttribLocation(program, "Position")
        val attribUv = glGetAttribLocation(program, "TexCoord")
        val attribColor = glGetAttribLocation(program, "Color")

        glEnableVertexAttribArray(attribPosition)
        glEnableVertexAttribArray(attribUv)
        glEnableVertexAttribArray(attribColor)

        glVertexAttribPointer(attribPosition, 2, GL_FLOAT, false, 20, 0)
        glVertexAttribPointer(attribUv, 2, GL_FLOAT, false, 20, 8)
        glVertexAttribPointer(attribColor, 4, GL_UNSIGNED_BYTE, true, 20, 16)

        val nullTexId = glGenTextures()
        nullTexture.texture().id(nullTexId)
        nullTexture.uv().set(0.5f, 0.5f)

        glBindTexture(GL_TEXTURE_2D, nullTexId)
        MemoryStack.stackPush().use {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, it.ints(Integer.MAX_VALUE))
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)

        glBindTexture(GL_TEXTURE_2D, 0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private fun createDefaultFont() {
        val bitmapW = 1024
        val bitmapH = 1024
        val fontHeight = 18f
        val fontTexId = glGenTextures()

        val fontInfo = STBTTFontinfo.create()
        val cdata = STBTTPackedchar.create(95)
        val ttf = ioResourceToByteBuffer("FiraSans-Regular.ttf", 160 * 1024)

        var scale = 0f
        var descent = 0f

        MemoryStack.stackPush().use {
            stbtt_InitFont(fontInfo, ttf)
            scale = stbtt_ScaleForPixelHeight(fontInfo, fontHeight)

            val d = it.mallocInt(1)
            stbtt_GetFontVMetrics(fontInfo, null, d, null)
            descent = d.get(0) * scale

            val bitmap = memAlloc(bitmapW * bitmapH)
            val pc = STBTTPackContext.mallocStack(it)
            stbtt_PackBegin(pc, bitmap, bitmapW, bitmapH, 0, 1, NULL)
            stbtt_PackSetOversampling(pc, 4, 4)
            stbtt_PackFontRange(pc, ttf, 0, fontHeight, 32, cdata)
            stbtt_PackEnd(pc)

            val texture = memAlloc(bitmapW * bitmapH * 4)
            (0..bitmap.capacity()).forEach { i ->
                val bit = bitmap.get(i)
                println(bit)
                //texture.putInt((bit shl 24) or 0x00FFFFFF)
            }
            texture.flip()

            glBindTexture(GL_TEXTURE_2D, fontTexId)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, bitmapW, bitmapH, 0, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)

            memFree(texture)
            memFree(bitmap)
        }

        with (defaultFont) {
            width(fun(_, _, text, len): Float {
                var textWidth = 0f
                MemoryStack.stackPush().use {
                    val unicode = it.mallocInt(1)

                    val glyphLen = nnk_utf_decode(text, memAddress(unicode), len)
                    val textLen = glyphLen

                    if (glyphLen == 0) {
                        return 0f
                    }

                    val advance = it.mallocInt(1)
                    while (textLen <= len && glyphLen != 0) {
                        if (unicode.get(0) == NK_UTF_INVALID) {
                            break
                        }

                        stbtt_GetCodepointHMetrics(fontInfo, unicode.get(0), advance, null)
                        textWidth += advance.get(0) * scale
                    }
                }

                return textWidth
            })
            height(fontHeight)
            query(fun(_, fontHeight, glyph, codepoint, _) {
                MemoryStack.stackPush().use {
                    val x = it.floats(0.0f)
                    val y = it.floats(0.0f)

                    val q = STBTTAlignedQuad.malloc()
                    val advance = memAllocInt(1)

                    stbtt_GetPackedQuad(cdata, bitmapW, bitmapH, codepoint - 32, x, y, q, false)
                    stbtt_GetCodepointHMetrics(fontInfo, codepoint, advance, null)

                    val ufg = NkUserFontGlyph.create(glyph)

                    ufg.width(q.x1() - q.x0())
                    ufg.height(q.y1() - q.y0())
                    ufg.offset().set(q.x0(), q.y0() + (fontHeight + descent))
                    ufg.xadvance(advance.get(0) * scale)
                    ufg.uv(0).set(q.s0(), q.t0())
                    ufg.uv(1).set(q.s1(), q.t1())

                    memFree(advance)
                    q.free()
                }
            })
            texture().id(fontTexId)
        }

        nk_style_set_font(context, defaultFont)
    }

    private fun ioResourceToByteBuffer(resource: String, bufferSize: Int): ByteBuffer {
        val path = Paths.get(resource)
        lateinit var buffer: ByteBuffer

        if (Files.isReadable(path)) {
            Files.newByteChannel(path).use {
                buffer = BufferUtils.createByteBuffer(it.size() as Int + 1)
                while (it.read(buffer) != -1) {
                }
            }
        } else {
            val source = Gui::class.java.classLoader.getResourceAsStream(resource)
            Channels.newChannel(source).use {
                buffer = BufferUtils.createByteBuffer(bufferSize)

                while (true) {
                    val bytes = it.read(buffer)
                    if (bytes == -1) {
                        break
                    }
                    if (buffer.remaining() == 0) {
                        buffer = resizeBuffer(buffer, buffer.capacity() * 3 / 2)
                    }
                }
            }
        }

        buffer.flip()
        return buffer.slice()
    }

    private fun resizeBuffer(buffer: ByteBuffer, newCapacity: Int): ByteBuffer {
        val newBuffer = BufferUtils.createByteBuffer(newCapacity)
        buffer.flip()
        newBuffer.put(buffer)
        return newBuffer
    }
}