package local.waze.cluster;

/** Start once per owned session; GuidanceSession persists ownership before calling this output. */
final class StandaloneHudOutput implements GuidanceSession.Output {
    interface Sender {void send(String packet);}
    private final Sender sender;
    private boolean started;
    StandaloneHudOutput(Sender sender){this.sender=sender;}
    public void guidance(GuidanceFrame frame) {
        String packet=StandaloneHudPackets.guidance(frame);
        if(packet==null)throw new IllegalArgumentException("Unsupported HUD guidance");
        if(!started){sender.send(StandaloneHudPackets.START);started=true;}
        sender.send(packet);
    }
    public void end(){sender.send(StandaloneHudPackets.END);started=false;}
}
