# SyncFlow Web - Desktop SMS Integration

Access your phone messages from your desktop browser. Built with Next.js, React, TypeScript, and Firebase.

## Features

- **QR Code Pairing**: Securely pair your phone with your desktop using QR codes
- **Real-time Sync**: Messages sync instantly between your phone and desktop
- **Send SMS**: Send text messages directly from your browser
- **Progressive Web App**: Install as a native app on macOS, Windows, or Linux
- **Dark Mode**: Automatic dark mode support
- **Responsive Design**: Works on desktop, tablet, and mobile browsers
- **End-to-End Secure**: Your messages are encrypted and only accessible by you

## Prerequisites

Before you begin, ensure you have:

- **Node.js** 18.0 or higher
- **npm** 9.0 or higher
- **Firebase project** set up (see parent directory's FIREBASE_SETUP.md)
- **SyncFlow Android app** installed on your phone

## Installation

### 1. Install Dependencies

```bash
cd web
npm install
```

### 2. Configure Firebase

Create a `.env.local` file in the `web` directory:

```bash
cp .env.example .env.local
```

Edit `.env.local` and add your Firebase configuration:

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your SyncFlow project
3. Go to **Project Settings** (⚙️ icon)
4. Scroll down to **Your apps** section
5. Click **Add app** → **Web** (</> icon)
6. Register app with nickname "SyncFlow Web"
7. Copy the configuration values to `.env.local`

### 3. Run Development Server

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser.

## Usage

### First Time Setup

1. **Open SyncFlow Web** in your browser
2. **On your phone**:
   - Open SyncFlow Android app
   - Go to **Settings → Desktop Integration**
   - Tap **"Pair New Device"**
   - A QR code will appear
3. **On your desktop**:
   - Click **"Start Scanning"**
   - Allow camera access
   - Point your webcam at the QR code on your phone
   - Or manually paste the pairing code
4. **Done!** You're now connected

### Sending Messages

1. Select a conversation from the left sidebar
2. Type your message in the input field at the bottom
3. Press **Enter** to send (or click the send button)
4. Your phone will send the SMS automatically

### Installing as Desktop App (PWA)

**Chrome/Edge:**
1. Click the install icon (⊕) in the address bar
2. Click **"Install"**
3. SyncFlow will appear in your applications

**Safari (macOS):**
1. Click **Share** → **Add to Dock**
2. SyncFlow will appear in your Dock

## Development

### Project Structure

```
web/
├── app/                    # Next.js App Router pages
│   ├── layout.tsx         # Root layout
│   ├── page.tsx           # Home/Pairing page
│   ├── messages/          # Messages page
│   └── globals.css        # Global styles
├── components/            # React components
│   ├── PairingScreen.tsx  # QR code pairing UI
│   ├── QRScanner.tsx      # Camera QR scanner
│   ├── Header.tsx         # App header
│   ├── ConversationList.tsx # Conversation sidebar
│   └── MessageView.tsx    # Message display and compose
├── lib/                   # Utilities and helpers
│   ├── firebase.ts        # Firebase configuration
│   └── store.ts           # Zustand state management
├── public/                # Static assets
│   └── manifest.json      # PWA manifest
└── package.json           # Dependencies
```

### Available Scripts

- `npm run dev` - Start development server (http://localhost:3000)
- `npm run build` - Build for production
- `npm start` - Start production server
- `npm run lint` - Run ESLint

### Tech Stack

- **Framework**: Next.js 14 (App Router)
- **Language**: TypeScript
- **Styling**: Tailwind CSS
- **State Management**: Zustand
- **Backend**: Firebase (Realtime Database, Auth, Storage)
- **Icons**: Lucide React
- **Date Formatting**: date-fns

## Deployment

### Deploy to Vercel (Recommended)

1. **Install Vercel CLI**:
   ```bash
   npm install -g vercel
   ```

2. **Login to Vercel**:
   ```bash
   vercel login
   ```

3. **Deploy**:
   ```bash
   vercel
   ```

4. **Add Environment Variables**:
   - Go to your Vercel project dashboard
   - Settings → Environment Variables
   - Add all variables from `.env.local`

5. **Redeploy** (if needed):
   ```bash
   vercel --prod
   ```

Your app will be live at: `https://your-project.vercel.app`

### Deploy to Netlify

1. **Install Netlify CLI**:
   ```bash
   npm install -g netlify-cli
   ```

2. **Build the app**:
   ```bash
   npm run build
   ```

3. **Deploy**:
   ```bash
   netlify deploy --prod
   ```

4. **Add Environment Variables**:
   - Go to Netlify dashboard
   - Site settings → Environment variables
   - Add all variables from `.env.local`

### Custom Domain

After deployment, you can add a custom domain:

**Vercel:**
1. Go to your project → Settings → Domains
2. Add your domain (e.g., `syncflow.app`)
3. Follow DNS configuration instructions

**Netlify:**
1. Go to Site settings → Domain management
2. Add custom domain
3. Configure DNS

## Security

- **Firebase Security Rules**: Ensure your Firebase Realtime Database rules are configured correctly (see `../firebase-rules.json`)
- **Environment Variables**: Never commit `.env.local` to version control
- **HTTPS**: Always use HTTPS in production
- **Camera Permissions**: Camera access is only used for QR code scanning

## Troubleshooting

### Camera not working

- **Check permissions**: Allow camera access in browser settings
- **Use manual pairing**: Paste the pairing code manually if camera fails
- **HTTPS required**: Camera API requires HTTPS (works on localhost)

### Messages not syncing

- **Check Firebase**: Verify Firebase is configured correctly
- **Check rules**: Ensure Firebase security rules allow authenticated users
- **Check connection**: Verify internet connection on both phone and desktop
- **Re-pair**: Try unpairing and pairing again

### Build errors

- **Clear cache**: `rm -rf .next node_modules && npm install`
- **Check Node version**: Ensure Node.js >= 18.0
- **Check env vars**: Verify all Firebase env variables are set

## Support

For issues or questions:
1. Check [Firebase Console](https://console.firebase.google.com/) for errors
2. Check browser console for errors
3. Review `../FIREBASE_SETUP.md` for Firebase configuration help

## License

This project is part of the SyncFlow suite.

## What's Next?

- **Email Integration**: Sync emails from your phone
- **File Transfer**: Drag & drop files between devices
- **Call Notifications**: Receive and manage calls from desktop
- **Clipboard Sync**: Share clipboard between devices
- **Multi-device**: Pair multiple desktops to one phone
