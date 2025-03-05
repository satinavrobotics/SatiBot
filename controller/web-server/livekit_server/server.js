import express from 'express';
import { AccessToken } from 'livekit-server-sdk';
import dotenv from 'dotenv';

dotenv.config();

const app = express();
app.use(express.json());

app.post('/createToken', async (req, res) => {
  const { participantName = 'quickstart-username', roomName = 'quickstart-room' } = req.body;
  console.log("Creating token for " + participantName + " at " + roomName );
  try {
    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
      identity: participantName,
      ttl: process.env.LIVEKIT_TTL, // 100 minutes in seconds
    });

    at.addGrant({ roomJoin: true, room: roomName });

    const token = await at.toJwt();
    res.json({ token });
  } catch (error) {
    res.status(500).json({ error: 'Error generating token', details: error.message });
  }
});

const PORT = process.env.LIVEKIT_PORT;
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
