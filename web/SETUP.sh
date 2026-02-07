#!/bin/bash

# SyncFlow Web - Setup Script
# This script will help you set up the SyncFlow web application

set -e

echo "======================================"
echo "  SyncFlow Web - Setup Script"
echo "======================================"
echo ""

# Check Node.js installation
echo "Checking Node.js installation..."
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed!"
    echo ""
    echo "Please install Node.js 18 or higher:"
    echo "  macOS: brew install node"
    echo "  Ubuntu: curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash - && sudo apt-get install -y nodejs"
    echo "  Or visit: https://nodejs.org/"
    exit 1
fi

NODE_VERSION=$(node -v)
echo "✅ Node.js $NODE_VERSION detected"

# Check npm installation
if ! command -v npm &> /dev/null; then
    echo "❌ npm is not installed!"
    exit 1
fi

NPM_VERSION=$(npm -v)
echo "✅ npm $NPM_VERSION detected"
echo ""

# Install dependencies
echo "Installing dependencies..."
npm install

if [ $? -eq 0 ]; then
    echo "✅ Dependencies installed successfully"
else
    echo "❌ Failed to install dependencies"
    exit 1
fi
echo ""

# Check for .env.local
if [ ! -f ".env.local" ]; then
    echo "⚠️  .env.local file not found"
    echo ""
    echo "Creating .env.local from .env.example..."
    cp .env.example .env.local
    echo "✅ .env.local created"
    echo ""
    echo "⚠️  IMPORTANT: You must configure Firebase settings in .env.local"
    echo ""
    echo "To configure Firebase:"
    echo "1. Go to https://console.firebase.google.com/"
    echo "2. Select your SyncFlow project"
    echo "3. Go to Project Settings → Your apps → Web app"
    echo "4. Copy the configuration values to .env.local"
    echo ""
    read -p "Press Enter when you've configured .env.local..."
else
    echo "✅ .env.local file found"
fi
echo ""

# Verify Firebase configuration
echo "Checking Firebase configuration..."
if grep -q "your_api_key_here" .env.local; then
    echo "⚠️  Firebase configuration not yet set up"
    echo ""
    echo "Please edit .env.local and add your Firebase credentials:"
    echo "  nano .env.local"
    echo ""
    read -p "Press Enter when you've configured Firebase..."
else
    echo "✅ Firebase configuration appears to be set"
fi
echo ""

# Ask if user wants to start dev server
echo "======================================"
echo "  Setup Complete!"
echo "======================================"
echo ""
echo "What would you like to do next?"
echo ""
echo "1. Start development server (npm run dev)"
echo "2. Build for production (npm run build)"
echo "3. Exit"
echo ""
read -p "Enter your choice (1-3): " choice

case $choice in
    1)
        echo ""
        echo "Starting development server..."
        echo "Open http://localhost:3000 in your browser"
        echo ""
        npm run dev
        ;;
    2)
        echo ""
        echo "Building for production..."
        npm run build
        if [ $? -eq 0 ]; then
            echo ""
            echo "✅ Build successful!"
            echo ""
            echo "To start the production server:"
            echo "  npm start"
            echo ""
            echo "To deploy:"
            echo "  See DEPLOYMENT.md for instructions"
        else
            echo "❌ Build failed"
            exit 1
        fi
        ;;
    3)
        echo ""
        echo "Setup complete! To start developing:"
        echo "  cd web"
        echo "  npm run dev"
        echo ""
        exit 0
        ;;
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac
