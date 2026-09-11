Repo containing a Proof-of-Concept application for off-grid communication via BLE (voice messages). The idea is to enhance text-based applications as such (e.g. BitChat) via voice messaging.
Instead of traditional audio codecs, the application uses EnCodec for audio compression and transmission (alongside some tricks for further compression).
The application smoothly runs on a POCO X5 and a RedMi-Note 10, both being very old smartphones...



### How this works

1. Download the app to two Android-based smartphones (make sure they are in developer mode)
2. On the top right corner you can expose or add devices (pairing) like contacts.
3. Click on a the specified contact to send a message

### More technical stuff

Smartphones equipped with the app are constantly advertising BLE via a dedicated server (even when not using the app) so that messages can constantly arrive (BLE is extremely lightweight in terms of energy consumption, so the battery should be ok). 
The app internally uses EnCodec's encoder for transmitting audio, enhanced with a simple trick

1. I've identified that EnCodec does not use all of its codewords so some are dead.
2. I've merged the so called "dead" codewords with the "active" ones, using the latter as anchors
3. Since this increases symbols' repetition, i'm using Zstd lossless compression that excels on compressing repetitive symbols
4. This further reduces bitrates without significant quality loss
5. EnCodec has been converted to ONNX (both encoder and decoder) for an easy integration to the app

The output of Zstd is being encrypted: on pairing, a SHA is being generated for secure communications.

I think this thingy can also work with LoRa networks, so that one can communicate via mesh networks with nearby peers as well as distant ones. The system has been tested in an apartment (10m distance between devices, many walls) and it runs smoothly. However, further increasing the distance between the devices might lead to one being unable to find the other, or any other peer. Furthermore, the application does now currently support relaying, however this should be an easy integration as the peer will know if the message is intended for this device, otherwise should forward it to another peer. Also, one can add dedup methods so that the same message does not make many backnforths between devices.

As said, this is just a proof of concept. Maybe further features (mostly described above) will follow

### ToDos
## Text
- [ ] Integrate a seperate tab for text
## Connectivity
- [ ] Add LoRa alternative (simultaneous transmission via LoRa and BLE)
- [ ] Add relaying (use a network of devices as such to receive and forward messages)
- [ ] Make sure the encryption works
- [ ] Introduce several peers as relays, see how long a message can travel and if the peers hold more than one times the same message (avoid back and forths)
## Integration to other devices
- [ ] Deploy on a RPi
- [ ] See weather the Pi can act as a relay
- [ ] This could also work for environmental audio monitoring and reporting whenever an unusual event occurs so that humans can have supervision as well
