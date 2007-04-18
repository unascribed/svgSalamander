/*
 * SVGUniverse.java
 *
 *
 *  The Salamander Project - 2D and 3D graphics libraries in Java
 *  Copyright (C) 2004 Mark McKay
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 *  Mark McKay can be contacted at mark@kitfox.com.  Salamander and other
 *  projects can be found at http://www.kitfox.com
 *
 * Created on February 18, 2004, 11:43 PM
 */

package com.kitfox.svg;

import com.kitfox.svg.app.beans.SVGIcon;
import java.awt.Graphics2D;
import java.net.*;
import java.awt.image.*;
import javax.imageio.*;
import java.beans.*;
import java.lang.ref.*;
//import java.util.*;
//import java.util.regex.*;

import java.util.*;
import java.io.*;
import org.xml.sax.*;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;
import org.xml.sax.helpers.XMLReaderFactory;

/**
 * Many SVG files can be loaded at one time.  These files will quite likely
 * need to reference one another.  The SVG universe provides a container for
 * all these files and the means for them to relate to each other.
 *
 * @author Mark McKay
 * @author <a href="mailto:mark@kitfox.com">Mark McKay</a>
 */
public class SVGUniverse implements Serializable
{
    public static final long serialVersionUID = 0;
    
    transient private PropertyChangeSupport changes = new PropertyChangeSupport(this);
    
    /**
     * Maps document URIs to their loaded SVG diagrams.  Note that URIs for
     * documents loaded from URLs will reflect their URLs and URIs for documents
     * initiated from streams will have the scheme <i>svgSalamander</i>.
     */
    final HashMap loadedDocs = new HashMap();
    
    final HashMap loadedFonts = new HashMap();
    
    final HashMap loadedImages = new HashMap();
    
    public static final String INPUTSTREAM_SCHEME = "svgSalamander";
    
    /**
     * Current time in this universe.  Used for resolving attributes that
     * are influenced by track information.  Time is in milliseconds.  Time
     * 0 coresponds to the time of 0 in each member diagram.
     */
    protected double curTime = 0.0;
    
    private boolean verbose = false;
    
    /** Creates a new instance of SVGUniverse */
    public SVGUniverse()
    {
    }
    
    public void addPropertyChangeListener(PropertyChangeListener l)
    {
        changes.addPropertyChangeListener(l);
    }
    
    public void removePropertyChangeListener(PropertyChangeListener l)
    {
        changes.removePropertyChangeListener(l);
    }
    
    /**
     * Release all loaded SVG document from memory
     */
    public void clear()
    {
        loadedDocs.clear();
        loadedFonts.clear();
        loadedImages.clear();
    }
    
    /**
     * Returns the current animation time in milliseconds.
     */
    public double getCurTime()
    { 
        return curTime; 
    }
    
    public void setCurTime(double curTime)
    {
        double oldTime = this.curTime;
        this.curTime = curTime; 
        changes.firePropertyChange("curTime", new Double(oldTime), new Double(curTime));
    }
    
    /**
     * Updates all time influenced style and presentation attributes in all SVG
     * documents in this universe.
     */
    public void updateTime() throws SVGException
    {
        for (Iterator it = loadedDocs.values().iterator(); it.hasNext();)
        {
            SVGDiagram dia = (SVGDiagram)it.next();
            dia.updateTime(curTime);
        }
    }
    
    /**
     * Called by the Font element to let the universe know that a font has been
     * loaded and is available.
     */
    void registerFont(Font font)
    {
        loadedFonts.put(font.getFontFace().getFontFamily(), font);
    }
    
    public Font getDefaultFont()
    {
        for (Iterator it = loadedFonts.values().iterator(); it.hasNext();)
        {
            return (Font)it.next();
        }
        return null;
    }
    
    public Font getFont(String fontName)
    {
        return (Font)loadedFonts.get(fontName);
    }
    
    void registerImage(URL imageURL)
    {
        if (loadedImages.containsKey(imageURL)) return;
        
        SoftReference ref;
        try
        {
            String fileName = imageURL.getFile();
            if (".svg".equals(fileName.substring(fileName.length() - 4).toLowerCase()))
            {
                SVGIcon icon = new SVGIcon();
                icon.setSvgURI(imageURL.toURI());
                
                BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                icon.paintIcon(null, g, 0, 0);
                g.dispose();
                ref = new SoftReference(img);
            }
            else
            {
                BufferedImage img = ImageIO.read(imageURL);
                ref = new SoftReference(img);
            }
            loadedImages.put(imageURL, ref);
        }
        catch (Exception e)
        {
            System.err.println("Could not load image: " + imageURL);
            e.printStackTrace();
        }
    }
    
    BufferedImage getImage(URL imageURL)
    {
        SoftReference ref = (SoftReference)loadedImages.get(imageURL);
        if (ref == null) return null;
        
        BufferedImage img = (BufferedImage)ref.get();
        //If image was cleared from memory, reload it
        if (img == null)
        {
            try
            {
                img = ImageIO.read(imageURL);
            }
            catch (Exception e)
            { e.printStackTrace(); }
            ref = new SoftReference(img);
            loadedImages.put(imageURL, ref);
        }
        
        return img;
    }
    
    /**
     * Returns the element of the document at the given URI.  If the document
     * is not already loaded, it will be.
     */
    public SVGElement getElement(URI path)
    {
        return getElement(path, true);
    }
    
    public SVGElement getElement(URL path)
    {
        try
        {
            URI uri = new URI(path.toString());
            return getElement(uri, true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * Looks up a href within our universe.  If the href refers to a document that
     * is not loaded, it will be loaded.  The URL #target will then be checked
     * against the SVG diagram's index and the coresponding element returned.
     * If there is no coresponding index, null is returned.
     */
    public SVGElement getElement(URI path, boolean loadIfAbsent)
    {
        try
        {
            //Strip fragment from URI
            URI xmlBase = new URI(path.getScheme(), path.getSchemeSpecificPart(), null);
            
            SVGDiagram dia = (SVGDiagram)loadedDocs.get(xmlBase);
            if (dia == null && loadIfAbsent)
            {
//System.err.println("SVGUnivserse: " + xmlBase.toString());
//javax.swing.JOptionPane.showMessageDialog(null, xmlBase.toString());
                URL url = xmlBase.toURL();
                
                loadSVG(url, false);
                dia = (SVGDiagram)loadedDocs.get(xmlBase);
                if (dia == null) return null;
            }
            
            String fragment = path.getFragment();
            return fragment == null ? dia.getRoot() : dia.getElement(fragment);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }
    
    public SVGDiagram getDiagram(URI xmlBase)
    {
        return getDiagram(xmlBase, true);
    }
    
    /**
     * Returns the diagram that has been loaded from this root.  If diagram is
     * not already loaded, returns null.
     */
    public SVGDiagram getDiagram(URI xmlBase, boolean loadIfAbsent)
    {
        if (xmlBase == null) return null;
        
        SVGDiagram dia = (SVGDiagram)loadedDocs.get(xmlBase);
        if (dia != null || !loadIfAbsent) return dia;
        
        //Load missing diagram
        try
        {
            URL url = xmlBase.toURL();
            
            loadSVG(url, false);
            dia = (SVGDiagram)loadedDocs.get(xmlBase);
            return dia;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        
        return null;
    }
    
    public URI loadSVG(URL docRoot)
    {
        return loadSVG(docRoot, false);
    }
    
    /**
     * Loads an SVG file and all the files it references from the URL provided.
     * If a referenced file already exists in the SVG universe, it is not
     * reloaded.
     * @param docRoot - URL to the location where this SVG file can be found.
     * @param forceLoad - if true, ignore cached diagram and reload
     * @return - The URI that refers to the loaded document
     */
    public URI loadSVG(URL docRoot, boolean forceLoad)
    {
        try
        {
            URI uri = new URI(docRoot.toString());
            if (loadedDocs.containsKey(uri) && !forceLoad) return uri;
            
            InputStream is = docRoot.openStream();
            return loadSVG(uri, new InputStreamReader(is));
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        
        return null;
    }
    
    
    public URI loadSVG(InputStream is, String name)
    {
        return loadSVG(is, name, false);
    }
    
    public URI loadSVG(InputStream is, String name, boolean forceLoad)
    {
        return loadSVG(new InputStreamReader(is), name, forceLoad);
    }
    
    public URI loadSVG(Reader reader, String name)
    {
        return loadSVG(reader, name, false);
    }
    
    /**
     * This routine allows you to create SVG documents from data streams that
     * may not necessarily have a URL to load from.  Since every SVG document
     * must be identified by a unique URL, Salamander provides a method to
     * fake this for streams by defining it's own protocol - svgSalamander -
     * for SVG documents without a formal URL.
     *
     * @param reader - A stream containing a valid SVG document
     * @param name - <p>A unique name for this document.  It will be used to
     * construct a unique URI to refer to this document and perform resolution
     * with relative URIs within this document.</p>
     * <p>For example, a name of "/myScene" will produce the URI
     * svgSalamander:/myScene.  "/maps/canada/toronto" will produce
     * svgSalamander:/maps/canada/toronto.  If this second document then
     * contained the href "../uk/london", it would resolve by default to
     * svgSalamander:/maps/uk/london.  That is, SVG Salamander defines the
     * URI scheme svgSalamander for it's own internal use and uses it
     * for uniquely identfying documents loaded by stream.</p>
     * <p>If you need to link to documents outside of this scheme, you can
     * either supply full hrefs (eg, href="url(http://www.kitfox.com/index.html)")
     * or put the xml:base attribute in a tag to change the defaultbase
     * URIs are resolved against</p>
     * <p>If a name does not start with the character '/', it will be automatically
     * prefixed to it.</p>
     * @param forceLoad - if true, ignore cached diagram and reload
     *
     * @return - The URI that refers to the loaded document
     */
    public URI loadSVG(Reader reader, String name, boolean forceLoad)
    {
//System.err.println(url.toString());
        //Synthesize URI for this stream
        URI uri = getStreamBuiltURI(name);
        if (uri == null) return null;
        if (loadedDocs.containsKey(uri) && !forceLoad) return uri;
        
        return loadSVG(uri, reader);
    }
    
    /**
     * Synthesize a URI for an SVGDiagram constructed from a stream.
     * @param name - Name given the document constructed from a stream.
     */
    public URI getStreamBuiltURI(String name)
    {
        if (name == null || name.length() == 0) return null;
        
        if (name.charAt(0) != '/') name = '/' + name;
        
        try
        {
            //Dummy URL for SVG documents built from image streams
            return new URI(INPUTSTREAM_SCHEME, name, null);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }
    
    
    protected URI loadSVG(URI xmlBase, Reader is)
    {
        // Use an instance of ourselves as the SAX event handler
        SVGLoader handler = new SVGLoader(xmlBase, this, verbose);
        
        //Place this docment in the universe before it is completely loaded
        // so that the load process can refer to references within it's current
        // document
//System.err.println("SVGUniverse: loading dia " + xmlBase);
        loadedDocs.put(xmlBase, handler.getLoadedDiagram());
        
        // Use the default (non-validating) parser
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        
        try
        {
            // Parse the input
            XMLReader reader = XMLReaderFactory.createXMLReader();
            reader.setEntityResolver(
                new EntityResolver()
                {
                    public InputSource resolveEntity(String publicId, String systemId)
                    {
                        //Ignore all DTDs
                        return new InputSource(new ByteArrayInputStream(new byte[0]));
                    }
                }
            );
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new BufferedReader(is)));
            
//            SAXParser saxParser = factory.newSAXParser();
//            saxParser.parse(new InputSource(new BufferedReader(is)), handler);
            return xmlBase;
        }
        catch (SAXParseException sex)
        {
            System.err.println("Error processing " + xmlBase);
            System.err.println(sex.getMessage());
            
            loadedDocs.remove(xmlBase);
            return null;
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        
        return null;
    }
    
    public static void main(String argv[])
    {
        try
        {
            URL url = new URL("svgSalamander", "localhost", -1, "abc.svg",
                    new URLStreamHandler()
            {
                protected URLConnection openConnection(URL u)
                {
                    return null;
                }
            }
            );
//            URL url2 = new URL("svgSalamander", "localhost", -1, "abc.svg");
            
            //Investigate URI resolution
            URI uriA, uriB, uriC, uriD, uriE;
            
            uriA = new URI("svgSalamander", "/names/mySpecialName", null);
//            uriA = new URI("http://www.kitfox.com/salamander");
//            uriA = new URI("svgSalamander://mySpecialName/grape");
            System.err.println(uriA.toString());
            System.err.println(uriA.getScheme());
            
            uriB = uriA.resolve("#begin");
            System.err.println(uriB.toString());
            
            uriC = uriA.resolve("tree#boing");
            System.err.println(uriC.toString());
            
            uriC = uriA.resolve("../tree#boing");
            System.err.println(uriC.toString());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public boolean isVerbose()
    {
        return verbose;
    }

    public void setVerbose(boolean verbose)
    {
        this.verbose = verbose;
    }
    
    /**
     * Uses serialization to duplicate this universe.
     */
    public SVGUniverse duplicate() throws IOException, ClassNotFoundException
    {
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        ObjectOutputStream os = new ObjectOutputStream(bs);
        os.writeObject(this);
        os.close();

        ByteArrayInputStream bin = new ByteArrayInputStream(bs.toByteArray());
        ObjectInputStream is = new ObjectInputStream(bin);
        SVGUniverse universe = (SVGUniverse)is.readObject();
        is.close();

        return universe;
    }
}
