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
    const ttl = parseInt(process.env.LIVEKIT_TTL, 10) || 6000; // Default to 100 minutes (6000 sec)
    const expirationTime = Math.floor(Date.now() / 1000) + ttl;

    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
      identity: participantName,
      ttl: ttl,
    });

    at.addGrant({ roomJoin: true, room: roomName });

    const token = await at.toJwt();
    
    res.json({
      token,
      expiration_time: expirationTime,
      server_url: process.env.LIVEKIT_SERVER_URL
    });
  } catch (error) {
    res.status(500).json({ error: 'Error generating token', details: error.message });
  }
});

const PORT = process.env.LIVEKIT_PORT || 3000;
app.listen(PORT, '0.0.0.0' ,() => {
  console.log(`Server running on port ${PORT}`);
});
