package com.lso;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

// The connection to the server. A thread of its own reads the lines and hands
// each one to the Swing thread, and says why when the connection ends. Host and
// port can be changed with -Dserver.host and -Dserver.port (the tests use
// another port, and in Docker the server is not on this machine).
public class ServerConnection
{
    private static final String HOST = System.getProperty("server.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("server.port", 8080);

    // Set by the network thread once connected, used by the Swing thread.
    private volatile PrintWriter out;

    // 'onLine' gets every line the server sends, 'onLost' the reason the
    // connection ended; both run on the Swing thread.
    public void start(Consumer<String> onLine, Consumer<String> onLost)
    {
        new Thread(() ->
        {
            boolean connected = false;
            boolean welcomed = false;

            try (Socket socket = new Socket(HOST, PORT);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
            {
                connected = true;
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while((line = in.readLine()) != null)
                {
                    if(line.startsWith("WELCOME"))
                    {
                        welcomed = true;
                    }

                    String msg = line;
                    SwingUtilities.invokeLater(() -> onLine.accept(msg));
                }
            }
            catch(Exception e)
            {
                System.err.println("Connection error: " + e.getMessage());
            }

            // Getting here means the connection is over. How far it got says
            // why: a full server closes it without even sending WELCOME.
            String problem;
            if(!connected)
            {
                problem = "Could not reach the server.";
            }
            else if(!welcomed)
            {
                problem = "The server closed the connection. It may be full.";
            }
            else
            {
                problem = "The connection to the server was lost.";
            }
            SwingUtilities.invokeLater(() -> onLost.accept(problem));
        }).start();
    }

    // A line to the server. Before the connection is up it is dropped: nothing
    // is sent before WELCOME anyway.
    public void send(String line)
    {
        if(out != null)
        {
            out.println(line);
        }
    }
}
