package com.lso;

public class NameCodec
{
    public static String encode(String name)
    {
        return name.replace(" ", "|");
    }

    public static String decode(String name)
    {
        return name.replace("|", " ");
    }
}
