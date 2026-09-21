package com.lso;

// The protocol separates tokens with a single space, so a name can't contain
// one. The client writes a space inside a name as %20, and a literal % as
// %25, and undoes it when it shows the name. The server never sees the
// difference: to it "il%20cacco" is just a name of printable characters.
public class NameCodec
{
    public static String encode(String name)
    {
        return name.replace("%", "%25").replace(" ", "%20");
    }

    // %20 goes first: a typed "%20" was sent as "%2520", which has no "%20"
    // in it, so it comes back as the literal "%20" and not as a space.
    public static String decode(String name)
    {
        return name.replace("%20", " ").replace("%25", "%");
    }
}
