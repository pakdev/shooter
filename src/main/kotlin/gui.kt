package com.petesburgh.shooter

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.nuklear.NkAllocator
import org.lwjgl.nuklear.NkContext
import org.lwjgl.nuklear.NkVec2
import org.lwjgl.nuklear.Nuklear.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL14.GL_FUNC_ADD
import org.lwjgl.opengl.GL14.glBlendEquation
import org.lwjgl.opengl.GL20.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Platform

class Gui constructor(window: Long) {

    val context: NkContext = initialize(window)

    companion object {
        val ALLOCATOR = NkAllocator.create()
        val MAX_VERTEX_BUFFER = 512 * 1024
        val MAX_ELEMENT_BUFFER = 128 * 1024

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

    fun render(useAA: Boolean) {
        glEnable(GL_BLEND)
        glBlendEquation(GL_FUNC_ADD)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_SCISSOR_TEST)
        glActiveTexture(GL_TEXTURE0)

        // setup program
        glUseProgram(prog)
        glUniform1i(uniform_tex, 0)

        MemoryStack.stackPush().use {
            glUniformMatrix4fv(uniform_proj, false, it.floats(
                    2.0f / width, 0.0f, 0.0f, 0.0f,
                    0.0f, -2.0f / height, 0.0f, 0.0f,
                    0.0f, 0.0f, -1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f, 1.0f
            ))
        }
        glViewport(0, 0, display_width, display_height)
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
        return context
    }

    private fun createDevice() {
        val shaderVersion = when (Platform.get()) {
            Platform.MACOSX -> "#version 150"
            else -> "#version 300 es"
        }

        val vertexShader = """
            $shaderVersion
            uniform mat4 ProjMatrix;
            in vec2 Position;
            in vec2 TexCoord;
            in vec4 Color;
            out vec2 Frag_UV;
            out vec4 Frag_Color;
            void main() {
                Frag_UV = TexCoord;
                Frag_Color = Color;
                gl_Position = ProjMatrix * vec4(Position.xy, 0, 1);
            }
            """.trimIndent()

        val fragmentShader = """
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
    }
}