package com.petesburgh.shooter

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.nuklear.Nuklear.*
import org.lwjgl.system.MemoryUtil.*

class Game {

    fun run() {
        // TODO: https://github.com/LWJGL/lwjgl3/blob/72a79be1bfa22bce8713583bcc75feec5d211699/modules/core/src/test/java/org/lwjgl/demo/nuklear/GLFWDemo.java
        val window  = initWindow()
        val gui = Gui(window)
        loop(window, gui)

        glfwFreeCallbacks(window)
        glfwDestroyWindow(window)

        glfwTerminate()
        glfwSetErrorCallback(null).free()
    }

    private fun initWindow(): Long {
        GLFWErrorCallback.createPrint(System.err).set()

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        val window = glfwCreateWindow(300, 300, "Hello World!", NULL, NULL)
        if (window == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        glfwSetKeyCallback(window, fun(window, key, scancode, action, mods) {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true)
            }
        })

        MemoryStack.stackPush().use {
            val pWidth = it.mallocInt(1)
            val pHeight = it.mallocInt(1)

            glfwGetWindowSize(window, pWidth, pHeight)

            val vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor())
            glfwSetWindowPos(window,
                (vidMode.width() - pWidth.get(0)) / 2,
                (vidMode.height() - pHeight.get(0)) / 2)
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1) // enable v-sync
        glfwShowWindow(window)
        return window
    }

    private fun loop(window: Long, gui: Gui) {
        GL.createCapabilities()

        glClearColor(1.0f, 0.0f, 0.0f, 0.0f)

        while (!glfwWindowShouldClose(window)) {
            nk_input_begin(gui.context)

            glfwPollEvents()
            when {
                gui.context.input().mouse().grab() -> glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)
                gui.context.input().mouse().grabbed() -> glfwSetCursorPos(window,
                        gui.context.input().mouse().prev().x() as Double,
                        gui.context.input().mouse().prev().y() as Double)
                gui.context.input().mouse().ungrab() -> glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
            }

            nk_input_end(gui.context)

            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            gui.render(true)

            glfwSwapBuffers(window)
        }
    }
}